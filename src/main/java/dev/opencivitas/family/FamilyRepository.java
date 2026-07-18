package dev.opencivitas.family;

import dev.opencivitas.database.Database;
import dev.opencivitas.navigation.SavedLocation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class FamilyRepository {
    private static final String MARRIAGE_SELECT = """
            SELECT marriage.id, marriage.spouse_a_uuid, spouse_a.last_name AS spouse_a_name,
                   marriage.spouse_b_uuid, spouse_b.last_name AS spouse_b_name,
                   marriage.officiant_uuid, marriage.married_at, marriage.dissolved_at,
                   marriage.dissolution_reason, marriage.home_world, marriage.home_x,
                   marriage.home_y, marriage.home_z, marriage.home_yaw, marriage.home_pitch,
                   COALESCE(pref_a.partner_pvp_enabled, 0) AS spouse_a_pvp,
                   COALESCE(pref_b.partner_pvp_enabled, 0) AS spouse_b_pvp
            FROM marriages marriage
            JOIN players spouse_a ON spouse_a.uuid = marriage.spouse_a_uuid
            JOIN players spouse_b ON spouse_b.uuid = marriage.spouse_b_uuid
            LEFT JOIN marriage_preferences pref_a
                ON pref_a.marriage_id = marriage.id AND pref_a.player_uuid = marriage.spouse_a_uuid
            LEFT JOIN marriage_preferences pref_b
                ON pref_b.marriage_id = marriage.id AND pref_b.player_uuid = marriage.spouse_b_uuid
            """;

    private final Database database;

    public FamilyRepository(Database database) {
        this.database = database;
    }

    public FamilyResult requestFriend(
            UUID requester, UUID target, Duration expiry, long now) throws SQLException {
        if (requester.equals(target)) return FamilyResult.CANNOT_TARGET_SELF;
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                expire(connection, now);
                if (!citizenExists(connection, requester) || !citizenExists(connection, target)) {
                    connection.rollback();
                    return FamilyResult.CITIZEN_NOT_FOUND;
                }
                if (friends(connection, requester, target)) {
                    connection.rollback();
                    return FamilyResult.ALREADY_FRIENDS;
                }
                if (pendingFriendRequest(connection, requester, target)
                        || pendingFriendRequest(connection, target, requester)) {
                    connection.rollback();
                    return FamilyResult.REQUEST_ALREADY_PENDING;
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO friend_requests(
                            requester_uuid, target_uuid, created_at, expires_at) VALUES (?, ?, ?, ?)
                        """)) {
                    statement.setString(1, requester.toString());
                    statement.setString(2, target.toString());
                    statement.setLong(3, now);
                    statement.setLong(4, Math.addExact(now, expiry.toMillis()));
                    statement.executeUpdate();
                }
                connection.commit();
                return FamilyResult.SUCCESS;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public FamilyResult respondFriend(
            UUID target, UUID requester, boolean accept, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                expire(connection, now);
                Long requestId = friendRequestId(connection, requester, target);
                if (requestId == null) {
                    connection.rollback();
                    return FamilyResult.REQUEST_NOT_FOUND;
                }
                if (accept) {
                    UUID[] pair = ordered(requester, target);
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT OR IGNORE INTO friendships(player_a_uuid, player_b_uuid, created_at)
                            VALUES (?, ?, ?)
                            """)) {
                        statement.setString(1, pair[0].toString());
                        statement.setString(2, pair[1].toString());
                        statement.setLong(3, now);
                        statement.executeUpdate();
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE friend_requests SET status = ?, responded_at = ? WHERE id = ? AND status = 'PENDING'
                        """)) {
                    statement.setString(1, accept ? "ACCEPTED" : "DECLINED");
                    statement.setLong(2, now);
                    statement.setLong(3, requestId);
                    if (statement.executeUpdate() != 1) throw new SQLException("Friend request changed");
                }
                connection.commit();
                return FamilyResult.SUCCESS;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<Friendship> friends(UUID playerId) throws SQLException {
        String sql = """
                SELECT CASE WHEN friendship.player_a_uuid = ? THEN friendship.player_b_uuid
                            ELSE friendship.player_a_uuid END AS friend_uuid,
                       friend.last_name, friendship.created_at
                FROM friendships friendship
                JOIN players friend ON friend.uuid = CASE
                    WHEN friendship.player_a_uuid = ? THEN friendship.player_b_uuid
                    ELSE friendship.player_a_uuid END
                WHERE friendship.player_a_uuid = ? OR friendship.player_b_uuid = ?
                ORDER BY friend.last_name COLLATE NOCASE
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 1; index <= 4; index++) statement.setString(index, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<Friendship> friends = new ArrayList<>();
                while (results.next()) friends.add(new Friendship(
                        UUID.fromString(results.getString("friend_uuid")), results.getString("last_name"),
                        Instant.ofEpochMilli(results.getLong("created_at"))));
                return List.copyOf(friends);
            }
        }
    }

    public FamilyResult removeFriend(UUID player, UUID friend) throws SQLException {
        UUID[] pair = ordered(player, friend);
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM friendships WHERE player_a_uuid = ? AND player_b_uuid = ?
                     """)) {
            statement.setString(1, pair[0].toString());
            statement.setString(2, pair[1].toString());
            return statement.executeUpdate() == 1 ? FamilyResult.SUCCESS : FamilyResult.NOT_FRIENDS;
        }
    }

    public FamilyResult proposeMarriage(
            UUID proposer, UUID target, Duration expiry, long now) throws SQLException {
        if (proposer.equals(target)) return FamilyResult.CANNOT_TARGET_SELF;
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                expire(connection, now);
                if (!citizenExists(connection, proposer) || !citizenExists(connection, target)) {
                    connection.rollback();
                    return FamilyResult.CITIZEN_NOT_FOUND;
                }
                if (activeMarriage(connection, proposer).isPresent()
                        || activeMarriage(connection, target).isPresent()) {
                    connection.rollback();
                    return FamilyResult.ALREADY_MARRIED;
                }
                if (activeProposal(connection, proposer, target)
                        || activeProposal(connection, target, proposer)) {
                    connection.rollback();
                    return FamilyResult.PROPOSAL_ALREADY_PENDING;
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO marriage_proposals(
                            proposer_uuid, target_uuid, created_at, expires_at) VALUES (?, ?, ?, ?)
                        """)) {
                    statement.setString(1, proposer.toString());
                    statement.setString(2, target.toString());
                    statement.setLong(3, now);
                    statement.setLong(4, Math.addExact(now, expiry.toMillis()));
                    statement.executeUpdate();
                }
                connection.commit();
                return FamilyResult.SUCCESS;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public FamilyResult respondMarriage(
            UUID target, UUID proposer, boolean accept, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                expire(connection, now);
                Long proposalId = proposalId(connection, proposer, target, "PENDING");
                if (proposalId == null) {
                    connection.rollback();
                    return FamilyResult.PROPOSAL_NOT_FOUND;
                }
                if (accept && (activeMarriage(connection, proposer).isPresent()
                        || activeMarriage(connection, target).isPresent())) {
                    connection.rollback();
                    return FamilyResult.ALREADY_MARRIED;
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE marriage_proposals SET status = ?, responded_at = ?
                        WHERE id = ? AND status = 'PENDING'
                        """)) {
                    statement.setString(1, accept ? "ACCEPTED" : "DECLINED");
                    statement.setLong(2, now);
                    statement.setLong(3, proposalId);
                    if (statement.executeUpdate() != 1) throw new SQLException("Marriage proposal changed");
                }
                connection.commit();
                return FamilyResult.SUCCESS;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public FamilyOperation<Marriage> officiate(
            UUID officiant,
            UUID first,
            UUID second,
            FamilyPolicy policy,
            long now
    ) throws SQLException {
        if (first.equals(second)) return FamilyOperation.result(FamilyResult.CANNOT_TARGET_SELF);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                expire(connection, now);
                if (!lawyer(connection, officiant, policy)) {
                    connection.rollback();
                    return FamilyOperation.result(FamilyResult.NOT_LAWYER);
                }
                if (!citizenExists(connection, first) || !citizenExists(connection, second)) {
                    connection.rollback();
                    return FamilyOperation.result(FamilyResult.CITIZEN_NOT_FOUND);
                }
                if (activeMarriage(connection, first).isPresent()
                        || activeMarriage(connection, second).isPresent()) {
                    connection.rollback();
                    return FamilyOperation.result(FamilyResult.ALREADY_MARRIED);
                }
                Long proposal = proposalId(connection, first, second, "ACCEPTED");
                if (proposal == null) proposal = proposalId(connection, second, first, "ACCEPTED");
                if (proposal == null) {
                    connection.rollback();
                    return FamilyOperation.result(FamilyResult.PROPOSAL_NOT_ACCEPTED);
                }
                UUID[] pair = ordered(first, second);
                long marriageId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO marriages(
                            spouse_a_uuid, spouse_b_uuid, officiant_uuid, married_at) VALUES (?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, pair[0].toString());
                    statement.setString(2, pair[1].toString());
                    statement.setString(3, officiant.toString());
                    statement.setLong(4, now);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("No generated marriage id");
                        marriageId = keys.getLong(1);
                    }
                }
                for (UUID spouse : pair) try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO marriage_preferences(
                            marriage_id, player_uuid, partner_pvp_enabled, updated_at) VALUES (?, ?, ?, ?)
                        """)) {
                    statement.setLong(1, marriageId);
                    statement.setString(2, spouse.toString());
                    statement.setInt(3, policy.partnerPvpEnabledByDefault() ? 1 : 0);
                    statement.setLong(4, now);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE marriage_proposals SET status = 'OFFICIATED', responded_at = ? WHERE id = ?
                        """)) {
                    statement.setLong(1, now);
                    statement.setLong(2, proposal);
                    statement.executeUpdate();
                }
                Marriage marriage = marriage(connection, marriageId).orElseThrow();
                connection.commit();
                return FamilyOperation.success(marriage);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public FamilyOperation<Marriage> divorce(
            UUID spouse, String reason, long now) throws SQLException {
        String normalized = reason.trim();
        if (normalized.isEmpty() || normalized.length() > 500) {
            return FamilyOperation.result(FamilyResult.INVALID_CONTENT);
        }
        try (Connection connection = database.openConnection()) {
            Optional<Marriage> selected = activeMarriage(connection, spouse);
            if (selected.isEmpty()) return FamilyOperation.result(FamilyResult.NOT_MARRIED);
            Marriage marriage = selected.orElseThrow();
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE marriages SET dissolved_at = ?, dissolved_by = ?, dissolution_reason = ?
                    WHERE id = ? AND dissolved_at IS NULL
                    """)) {
                statement.setLong(1, now);
                statement.setString(2, spouse.toString());
                statement.setString(3, normalized);
                statement.setLong(4, marriage.id());
                if (statement.executeUpdate() != 1) throw new SQLException("Marriage changed during divorce");
            }
            return FamilyOperation.success(marriage);
        }
    }

    public FamilyOperation<Marriage> setHome(
            UUID spouse, SavedLocation home, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            Optional<Marriage> selected = activeMarriage(connection, spouse);
            if (selected.isEmpty()) return FamilyOperation.result(FamilyResult.NOT_MARRIED);
            Marriage marriage = selected.orElseThrow();
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE marriages SET home_world = ?, home_x = ?, home_y = ?, home_z = ?,
                                         home_yaw = ?, home_pitch = ?
                    WHERE id = ? AND dissolved_at IS NULL
                    """)) {
                statement.setString(1, home.world());
                statement.setDouble(2, home.x());
                statement.setDouble(3, home.y());
                statement.setDouble(4, home.z());
                statement.setFloat(5, home.yaw());
                statement.setFloat(6, home.pitch());
                statement.setLong(7, marriage.id());
                if (statement.executeUpdate() != 1) throw new SQLException("Marriage changed while setting home");
            }
            return FamilyOperation.success(activeMarriage(connection, spouse).orElseThrow());
        }
    }

    public FamilyOperation<Marriage> setPartnerPvp(
            UUID spouse, boolean enabled, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            Optional<Marriage> selected = activeMarriage(connection, spouse);
            if (selected.isEmpty()) return FamilyOperation.result(FamilyResult.NOT_MARRIED);
            Marriage marriage = selected.orElseThrow();
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE marriage_preferences SET partner_pvp_enabled = ?, updated_at = ?
                    WHERE marriage_id = ? AND player_uuid = ?
                    """)) {
                statement.setInt(1, enabled ? 1 : 0);
                statement.setLong(2, now);
                statement.setLong(3, marriage.id());
                statement.setString(4, spouse.toString());
                if (statement.executeUpdate() != 1) throw new SQLException("Marriage preference missing");
            }
            return FamilyOperation.success(activeMarriage(connection, spouse).orElseThrow());
        }
    }

    public Optional<Marriage> activeMarriage(UUID spouse) throws SQLException {
        try (Connection connection = database.openConnection()) {
            return activeMarriage(connection, spouse);
        }
    }

    public List<Marriage> activeMarriages() throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     MARRIAGE_SELECT + " WHERE marriage.dissolved_at IS NULL ORDER BY marriage.id");
             ResultSet results = statement.executeQuery()) {
            List<Marriage> marriages = new ArrayList<>();
            while (results.next()) marriages.add(readMarriage(results));
            return List.copyOf(marriages);
        }
    }

    private static Optional<Marriage> activeMarriage(Connection connection, UUID spouse) throws SQLException {
        String sql = MARRIAGE_SELECT + " WHERE marriage.dissolved_at IS NULL "
                + "AND (marriage.spouse_a_uuid = ? OR marriage.spouse_b_uuid = ?) LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, spouse.toString());
            statement.setString(2, spouse.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readMarriage(results)) : Optional.empty();
            }
        }
    }

    private static Optional<Marriage> marriage(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                MARRIAGE_SELECT + " WHERE marriage.id = ?")) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readMarriage(results)) : Optional.empty();
            }
        }
    }

    private static Marriage readMarriage(ResultSet results) throws SQLException {
        long dissolved = results.getLong("dissolved_at");
        boolean active = results.wasNull();
        String homeWorld = results.getString("home_world");
        SavedLocation home = homeWorld == null ? null : new SavedLocation(
                "partner-home", homeWorld, results.getDouble("home_x"), results.getDouble("home_y"),
                results.getDouble("home_z"), results.getFloat("home_yaw"), results.getFloat("home_pitch"));
        return new Marriage(results.getLong("id"), UUID.fromString(results.getString("spouse_a_uuid")),
                results.getString("spouse_a_name"), UUID.fromString(results.getString("spouse_b_uuid")),
                results.getString("spouse_b_name"), UUID.fromString(results.getString("officiant_uuid")),
                Instant.ofEpochMilli(results.getLong("married_at")),
                active ? null : Instant.ofEpochMilli(dissolved), results.getString("dissolution_reason"),
                home, results.getInt("spouse_a_pvp") == 1, results.getInt("spouse_b_pvp") == 1);
    }

    private static void expire(Connection connection, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE friend_requests SET status = 'EXPIRED', responded_at = ?
                WHERE status = 'PENDING' AND expires_at <= ?
                """)) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE marriage_proposals SET status = 'EXPIRED', responded_at = ?
                WHERE status IN ('PENDING', 'ACCEPTED') AND expires_at <= ?
                """)) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.executeUpdate();
        }
    }

    private static boolean pendingFriendRequest(Connection connection, UUID requester, UUID target)
            throws SQLException {
        return friendRequestId(connection, requester, target) != null;
    }

    private static Long friendRequestId(Connection connection, UUID requester, UUID target)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM friend_requests
                WHERE requester_uuid = ? AND target_uuid = ? AND status = 'PENDING'
                ORDER BY created_at DESC, id DESC LIMIT 1
                """)) {
            statement.setString(1, requester.toString());
            statement.setString(2, target.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getLong(1) : null;
            }
        }
    }

    private static boolean activeProposal(Connection connection, UUID proposer, UUID target)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM marriage_proposals
                WHERE proposer_uuid = ? AND target_uuid = ? AND status IN ('PENDING', 'ACCEPTED') LIMIT 1
                """)) {
            statement.setString(1, proposer.toString());
            statement.setString(2, target.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static Long proposalId(
            Connection connection, UUID proposer, UUID target, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM marriage_proposals
                WHERE proposer_uuid = ? AND target_uuid = ? AND status = ?
                ORDER BY created_at DESC, id DESC LIMIT 1
                """)) {
            statement.setString(1, proposer.toString());
            statement.setString(2, target.toString());
            statement.setString(3, status);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getLong(1) : null;
            }
        }
    }

    private static boolean friends(Connection connection, UUID first, UUID second) throws SQLException {
        UUID[] pair = ordered(first, second);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM friendships WHERE player_a_uuid = ? AND player_b_uuid = ?
                """)) {
            statement.setString(1, pair[0].toString());
            statement.setString(2, pair[1].toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static boolean lawyer(Connection connection, UUID playerId, FamilyPolicy policy)
            throws SQLException {
        if (!policy.lawyerJobs().isEmpty()) {
            String sql = "SELECT 1 FROM citizen_jobs WHERE player_uuid = ? AND job_id IN ("
                    + placeholders(policy.lawyerJobs().size()) + ") LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerId.toString());
                bind(statement, policy.lawyerJobs(), 2);
                try (ResultSet results = statement.executeQuery()) {
                    if (results.next()) return true;
                }
            }
        }
        if (!policy.lawyerQualifications().isEmpty()) {
            String sql = "SELECT 1 FROM qualifications WHERE player_uuid = ? AND qualification_id IN ("
                    + placeholders(policy.lawyerQualifications().size()) + ") LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerId.toString());
                bind(statement, policy.lawyerQualifications(), 2);
                try (ResultSet results = statement.executeQuery()) {
                    return results.next();
                }
            }
        }
        return false;
    }

    private static boolean citizenExists(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static void bind(PreparedStatement statement, List<String> values, int start) throws SQLException {
        for (int index = 0; index < values.size(); index++) statement.setString(start + index, values.get(index));
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static UUID[] ordered(UUID first, UUID second) {
        return first.toString().compareTo(second.toString()) < 0
                ? new UUID[]{first, second} : new UUID[]{second, first};
    }
}
