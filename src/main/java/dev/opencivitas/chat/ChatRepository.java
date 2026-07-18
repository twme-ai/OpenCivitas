package dev.opencivitas.chat;

import dev.opencivitas.database.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ChatRepository {
    private final Database database;

    public ChatRepository(Database database) {
        this.database = database;
    }

    public ChatChannel preference(UUID playerId) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT active_channel FROM chat_preferences WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) return ChatChannel.GLOBAL;
                try {
                    return ChatChannel.valueOf(results.getString(1));
                } catch (IllegalArgumentException exception) {
                    return ChatChannel.GLOBAL;
                }
            }
        }
    }

    public ChatResult setPreference(UUID playerId, ChatChannel channel, long now) throws SQLException {
        String sql = """
                INSERT INTO chat_preferences(player_uuid, active_channel, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                    active_channel = excluded.active_channel, updated_at = excluded.updated_at
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (!citizenExists(connection, playerId)) return ChatResult.CITIZEN_NOT_FOUND;
            statement.setString(1, playerId.toString());
            statement.setString(2, channel.name());
            statement.setLong(3, now);
            statement.executeUpdate();
            return ChatResult.SUCCESS;
        }
    }

    public ChatResult touchConversation(UUID senderId, UUID recipientId, long now) throws SQLException {
        if (senderId.equals(recipientId)) return ChatResult.CANNOT_MESSAGE_SELF;
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!citizenExists(connection, senderId) || !citizenExists(connection, recipientId)) {
                    connection.rollback();
                    return ChatResult.CITIZEN_NOT_FOUND;
                }
                upsertContact(connection, senderId, recipientId, now);
                upsertContact(connection, recipientId, senderId, now);
                connection.commit();
                return ChatResult.SUCCESS;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public Optional<UUID> replyTarget(UUID playerId) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT reply_target_uuid FROM chat_contacts WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(UUID.fromString(results.getString(1))) : Optional.empty();
            }
        }
    }

    public ChatOperation<MailMessage> sendMail(
            UUID senderId, UUID recipientId, String content, int maximumLength, long now) throws SQLException {
        String normalized = content.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            return ChatOperation.result(ChatResult.INVALID_CONTENT);
        }
        if (senderId.equals(recipientId)) return ChatOperation.result(ChatResult.CANNOT_MESSAGE_SELF);
        try (Connection connection = database.openConnection()) {
            String senderName = playerName(connection, senderId).orElse(null);
            if (senderName == null || !citizenExists(connection, recipientId)) {
                return ChatOperation.result(ChatResult.CITIZEN_NOT_FOUND);
            }
            String sql = """
                    INSERT INTO mail_messages(sender_uuid, recipient_uuid, content, sent_at)
                    VALUES (?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, senderId.toString());
                statement.setString(2, recipientId.toString());
                statement.setString(3, normalized);
                statement.setLong(4, now);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("No generated mail id");
                    return ChatOperation.success(new MailMessage(keys.getLong(1), senderId,
                            senderName, recipientId, normalized, Instant.ofEpochMilli(now), null));
                }
            }
        }
    }

    public List<MailMessage> inbox(UUID recipientId, int limit, int offset) throws SQLException {
        String sql = """
                SELECT mail.id, mail.sender_uuid, sender.last_name, mail.content,
                       mail.sent_at, mail.read_at
                FROM mail_messages mail JOIN players sender ON sender.uuid = mail.sender_uuid
                WHERE mail.recipient_uuid = ? AND mail.deleted_at IS NULL
                ORDER BY mail.sent_at DESC, mail.id DESC LIMIT ? OFFSET ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, recipientId.toString());
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            try (ResultSet results = statement.executeQuery()) {
                List<MailMessage> mail = new ArrayList<>();
                while (results.next()) mail.add(readMail(results, recipientId));
                return List.copyOf(mail);
            }
        }
    }

    public ChatOperation<MailMessage> readMail(UUID recipientId, long mailId, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                MailMessage mail;
                String sql = """
                        SELECT mail.id, mail.sender_uuid, sender.last_name, mail.content,
                               mail.sent_at, mail.read_at
                        FROM mail_messages mail JOIN players sender ON sender.uuid = mail.sender_uuid
                        WHERE mail.id = ? AND mail.recipient_uuid = ? AND mail.deleted_at IS NULL
                        """;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setLong(1, mailId);
                    statement.setString(2, recipientId.toString());
                    try (ResultSet results = statement.executeQuery()) {
                        if (!results.next()) {
                            connection.rollback();
                            return ChatOperation.result(ChatResult.MAIL_NOT_FOUND);
                        }
                        mail = readMail(results, recipientId);
                    }
                }
                if (mail.readAt() == null) try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE mail_messages SET read_at = ? WHERE id = ? AND read_at IS NULL")) {
                    statement.setLong(1, now);
                    statement.setLong(2, mailId);
                    statement.executeUpdate();
                    mail = new MailMessage(mail.id(), mail.senderId(), mail.senderName(), recipientId,
                            mail.content(), mail.sentAt(), Instant.ofEpochMilli(now));
                }
                connection.commit();
                return ChatOperation.success(mail);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public ChatResult deleteMail(UUID recipientId, long mailId, long now) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE mail_messages SET deleted_at = ?
                     WHERE id = ? AND recipient_uuid = ? AND deleted_at IS NULL
                     """)) {
            statement.setLong(1, now);
            statement.setLong(2, mailId);
            statement.setString(3, recipientId.toString());
            return statement.executeUpdate() == 1 ? ChatResult.SUCCESS : ChatResult.MAIL_NOT_FOUND;
        }
    }

    public int unreadMail(UUID recipientId) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM mail_messages
                     WHERE recipient_uuid = ? AND read_at IS NULL AND deleted_at IS NULL
                     """)) {
            statement.setString(1, recipientId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        }
    }

    public AdvertisementAttempt submitAdvertisement(
            UUID advertiserId,
            String content,
            int maximumLength,
            Duration cooldown,
            long now
    ) throws SQLException {
        String normalized = content.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength) {
            return AdvertisementAttempt.result(ChatResult.INVALID_CONTENT);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                String advertiserName = playerName(connection, advertiserId).orElse(null);
                if (advertiserName == null) {
                    connection.rollback();
                    return AdvertisementAttempt.result(ChatResult.CITIZEN_NOT_FOUND);
                }
                if (!hasQualification(connection, advertiserId, "entrepreneur")) {
                    connection.rollback();
                    return AdvertisementAttempt.result(ChatResult.MISSING_QUALIFICATION);
                }
                long latest = latestAdvertisement(connection, advertiserId);
                long readyAt = latest + cooldown.toMillis();
                if (latest > 0 && now < readyAt) {
                    connection.rollback();
                    return AdvertisementAttempt.cooldown(readyAt - now);
                }
                String sql = "INSERT INTO advertisements(advertiser_uuid, content, created_at) VALUES (?, ?, ?)";
                try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, advertiserId.toString());
                    statement.setString(2, normalized);
                    statement.setLong(3, now);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("No generated advertisement id");
                        Advertisement advertisement = new Advertisement(keys.getLong(1), advertiserId,
                                advertiserName, normalized, Instant.ofEpochMilli(now));
                        connection.commit();
                        return AdvertisementAttempt.success(advertisement);
                    }
                }
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public boolean isDepartmentMember(
            UUID playerId, DepartmentChannelDefinition department, long now) throws SQLException {
        return departmentMembers(department, now).contains(playerId);
    }

    public Set<UUID> departmentMembers(
            DepartmentChannelDefinition department, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            Set<UUID> members = new LinkedHashSet<>();
            if (!department.jobIds().isEmpty()) {
                String placeholders = placeholders(department.jobIds().size());
                String sql = "SELECT DISTINCT player_uuid FROM citizen_jobs WHERE job_id IN (" + placeholders + ")";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    bindStrings(statement, department.jobIds(), 1);
                    try (ResultSet results = statement.executeQuery()) {
                        while (results.next()) members.add(UUID.fromString(results.getString(1)));
                    }
                }
            }
            if (!department.officeIds().isEmpty()) {
                String placeholders = placeholders(department.officeIds().size());
                String sql = "SELECT DISTINCT holder_uuid FROM office_terms WHERE office_id IN (" + placeholders
                        + ") AND status = 'ACTIVE' AND ends_at > ?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    int next = bindStrings(statement, department.officeIds(), 1);
                    statement.setLong(next, now);
                    try (ResultSet results = statement.executeQuery()) {
                        while (results.next()) members.add(UUID.fromString(results.getString(1)));
                    }
                }
            }
            return Set.copyOf(members);
        }
    }

    private static MailMessage readMail(ResultSet results, UUID recipientId) throws SQLException {
        long readAt = results.getLong("read_at");
        boolean unread = results.wasNull();
        return new MailMessage(results.getLong("id"), UUID.fromString(results.getString("sender_uuid")),
                results.getString("last_name"), recipientId, results.getString("content"),
                Instant.ofEpochMilli(results.getLong("sent_at")),
                unread ? null : Instant.ofEpochMilli(readAt));
    }

    private static void upsertContact(
            Connection connection, UUID playerId, UUID targetId, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat_contacts(player_uuid, reply_target_uuid, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                    reply_target_uuid = excluded.reply_target_uuid, updated_at = excluded.updated_at
                """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, targetId.toString());
            statement.setLong(3, now);
            statement.executeUpdate();
        }
    }

    private static boolean citizenExists(Connection connection, UUID playerId) throws SQLException {
        return playerName(connection, playerId).isPresent();
    }

    private static Optional<String> playerName(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT last_name FROM players WHERE uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(results.getString(1)) : Optional.empty();
            }
        }
    }

    private static boolean hasQualification(
            Connection connection, UUID playerId, String qualification) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM qualifications WHERE player_uuid = ? AND qualification_id = ? LIMIT 1
                """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, qualification);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static long latestAdvertisement(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT created_at FROM advertisements WHERE advertiser_uuid = ?
                ORDER BY created_at DESC, id DESC LIMIT 1
                """)) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getLong(1) : 0;
            }
        }
    }

    private static int bindStrings(PreparedStatement statement, List<String> values, int start)
            throws SQLException {
        int index = start;
        for (String value : values) statement.setString(index++, value.toLowerCase(Locale.ROOT));
        return index;
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
}
