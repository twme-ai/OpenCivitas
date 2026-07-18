package dev.opencivitas.citizen;

import dev.opencivitas.database.Database;
import dev.opencivitas.economy.AccountRegistration;
import dev.opencivitas.economy.BalanceRank;
import dev.opencivitas.economy.LedgerEntry;
import dev.opencivitas.economy.LedgerEntryType;
import dev.opencivitas.economy.TransferResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class CitizenRepository {
    private final Database database;

    public CitizenRepository(Database database) {
        this.database = database;
    }

    public List<BalanceRank> balanceTop(int limit, int offset) throws SQLException {
        String sql = """
                SELECT p.uuid, p.last_name, a.balance_cents
                FROM accounts a JOIN players p ON p.uuid = a.player_uuid
                ORDER BY a.balance_cents DESC, p.last_name COLLATE NOCASE, p.uuid
                LIMIT ? OFFSET ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            statement.setInt(2, offset);
            try (ResultSet results = statement.executeQuery()) {
                List<BalanceRank> ranks = new ArrayList<>();
                while (results.next()) {
                    ranks.add(new BalanceRank(
                            UUID.fromString(results.getString("uuid")),
                            results.getString("last_name"), results.getLong("balance_cents")));
                }
                return List.copyOf(ranks);
            }
        }
    }

    public AccountRegistration register(UUID uuid, String name, String clientLocale, long startingCents)
            throws SQLException {
        long now = Instant.now().toEpochMilli();
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                upsertPlayer(connection, uuid, name, clientLocale, now);
                boolean created = insertAccount(connection, uuid, startingCents);
                if (created && startingCents > 0) {
                    insertLedger(connection, uuid, null, startingCents, LedgerEntryType.STARTING_BALANCE, now);
                }
                AccountRegistration registration = readRegistration(connection, uuid, created);
                connection.commit();
                return registration;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public Optional<CitizenProfile> find(UUID uuid) throws SQLException {
        return findBy("p.uuid = ?", uuid.toString());
    }

    public Optional<CitizenProfile> findByName(String name) throws SQLException {
        return findBy("p.last_name = ? COLLATE NOCASE", name);
    }

    public void updateLastSeen(UUID uuid) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE players SET last_seen_at = ? WHERE uuid = ?")) {
            statement.setLong(1, Instant.now().toEpochMilli());
            statement.setString(2, uuid.toString());
            statement.executeUpdate();
        }
    }

    public void startActivitySession(UUID uuid, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement close = connection.prepareStatement("""
                        UPDATE player_activity_sessions SET ended_at = last_activity_at
                        WHERE player_uuid = ? AND ended_at IS NULL
                        """)) {
                    close.setString(1, uuid.toString());
                    close.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO player_activity_sessions(player_uuid, started_at, last_activity_at)
                        VALUES (?, ?, ?)
                        """)) {
                    insert.setString(1, uuid.toString());
                    insert.setLong(2, now);
                    insert.setLong(3, now);
                    insert.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public void heartbeatActivity(UUID uuid, long now) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE player_activity_sessions SET last_activity_at = ?
                     WHERE id = (
                         SELECT id FROM player_activity_sessions
                         WHERE player_uuid = ? AND ended_at IS NULL
                         ORDER BY id DESC LIMIT 1
                     )
                     """)) {
            statement.setLong(1, now);
            statement.setString(2, uuid.toString());
            statement.executeUpdate();
        }
    }

    public void endActivitySession(UUID uuid, long now) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE player_activity_sessions SET last_activity_at = ?, ended_at = ?
                     WHERE id = (
                         SELECT id FROM player_activity_sessions
                         WHERE player_uuid = ? AND ended_at IS NULL
                         ORDER BY id DESC LIMIT 1
                     )
                     """)) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.setString(3, uuid.toString());
            statement.executeUpdate();
        }
    }

    public CitizenActivity activity(UUID uuid, long recentSince) throws SQLException {
        String sql = """
                SELECT
                    COALESCE(SUM(last_activity_at - started_at), 0) AS total_millis,
                    COALESCE(SUM(CASE
                        WHEN last_activity_at <= ? THEN 0
                        WHEN started_at < ? THEN last_activity_at - ?
                        ELSE last_activity_at - started_at
                    END), 0) AS recent_millis
                FROM player_activity_sessions WHERE player_uuid = ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, recentSince);
            statement.setLong(2, recentSince);
            statement.setLong(3, recentSince);
            statement.setString(4, uuid.toString());
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return new CitizenActivity(
                        Duration.ofMillis(results.getLong("total_millis")),
                        Duration.ofMillis(results.getLong("recent_millis")));
            }
        }
    }

    public void setPreferredLocale(UUID uuid, String locale) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE players SET preferred_locale = ? WHERE uuid = ?")) {
            if (locale == null) {
                statement.setNull(1, java.sql.Types.VARCHAR);
            } else {
                statement.setString(1, locale);
            }
            statement.setString(2, uuid.toString());
            statement.executeUpdate();
        }
    }

    public TransferResult transfer(UUID sender, UUID recipient, long amountCents) throws SQLException {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive");
        }
        long now = Instant.now().toEpochMilli();
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                int withdrawn = withdraw(connection, sender, amountCents);
                if (withdrawn == 0) {
                    TransferResult result = accountExists(connection, sender)
                            ? new TransferResult(TransferResult.Status.INSUFFICIENT_FUNDS, balance(connection, sender))
                            : new TransferResult(TransferResult.Status.ACCOUNT_NOT_FOUND, 0);
                    connection.rollback();
                    return result;
                }
                if (deposit(connection, recipient, amountCents) == 0) {
                    connection.rollback();
                    return new TransferResult(TransferResult.Status.ACCOUNT_NOT_FOUND, 0);
                }
                insertLedger(connection, sender, recipient, -amountCents, LedgerEntryType.PAYMENT, now);
                insertLedger(connection, recipient, sender, amountCents, LedgerEntryType.PAYMENT, now);
                long senderBalance = balance(connection, sender);
                connection.commit();
                return new TransferResult(TransferResult.Status.SUCCESS, senderBalance);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<LedgerEntry> transactions(UUID uuid, int limit, int offset) throws SQLException {
        String sql = """
                SELECT l.id, l.amount_cents, l.entry_type, l.created_at, p.last_name AS counterparty_name
                FROM ledger_entries l
                LEFT JOIN players p ON p.uuid = l.counterparty_uuid
                WHERE l.player_uuid = ?
                ORDER BY l.created_at DESC, l.id DESC
                LIMIT ? OFFSET ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            try (ResultSet results = statement.executeQuery()) {
                List<LedgerEntry> entries = new ArrayList<>();
                while (results.next()) {
                    entries.add(new LedgerEntry(
                            results.getLong("id"),
                            results.getLong("amount_cents"),
                            LedgerEntryType.valueOf(results.getString("entry_type")),
                            results.getString("counterparty_name"),
                            Instant.ofEpochMilli(results.getLong("created_at"))
                    ));
                }
                return List.copyOf(entries);
            }
        }
    }

    private Optional<CitizenProfile> findBy(String predicate, String value) throws SQLException {
        String sql = """
                SELECT p.uuid, p.last_name, p.joined_at, p.last_seen_at, p.preferred_locale, a.balance_cents
                FROM players p
                JOIN accounts a ON a.player_uuid = p.uuid
                WHERE %s
                ORDER BY p.last_seen_at DESC
                LIMIT 1
                """.formatted(predicate);
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                return Optional.of(new CitizenProfile(
                        UUID.fromString(results.getString("uuid")),
                        results.getString("last_name"),
                        Instant.ofEpochMilli(results.getLong("joined_at")),
                        Instant.ofEpochMilli(results.getLong("last_seen_at")),
                        results.getString("preferred_locale"),
                        results.getLong("balance_cents")
                ));
            }
        }
    }

    private static void upsertPlayer(Connection connection, UUID uuid, String name, String locale, long now)
            throws SQLException {
        String sql = """
                INSERT INTO players(uuid, last_name, client_locale, joined_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    last_name = excluded.last_name,
                    client_locale = excluded.client_locale,
                    last_seen_at = excluded.last_seen_at
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, name);
            statement.setString(3, locale);
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private static boolean insertAccount(Connection connection, UUID uuid, long startingCents) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO accounts(player_uuid, balance_cents) VALUES (?, ?)")) {
            statement.setString(1, uuid.toString());
            statement.setLong(2, startingCents);
            return statement.executeUpdate() == 1;
        }
    }

    private static AccountRegistration readRegistration(Connection connection, UUID uuid, boolean created)
            throws SQLException {
        String sql = """
                SELECT a.balance_cents, p.preferred_locale
                FROM accounts a JOIN players p ON p.uuid = a.player_uuid
                WHERE a.player_uuid = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("Registered account could not be read");
                }
                return new AccountRegistration(created, results.getLong(1), results.getString(2));
            }
        }
    }

    private static int withdraw(Connection connection, UUID uuid, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance_cents = balance_cents - ? "
                        + "WHERE player_uuid = ? AND balance_cents >= ?")) {
            statement.setLong(1, amount);
            statement.setString(2, uuid.toString());
            statement.setLong(3, amount);
            return statement.executeUpdate();
        }
    }

    private static int deposit(Connection connection, UUID uuid, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance_cents = balance_cents + ? WHERE player_uuid = ?")) {
            statement.setLong(1, amount);
            statement.setString(2, uuid.toString());
            return statement.executeUpdate();
        }
    }

    private static boolean accountExists(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM accounts WHERE player_uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static long balance(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_cents FROM accounts WHERE player_uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("Account not found");
                }
                return results.getLong(1);
            }
        }
    }

    private static void insertLedger(
            Connection connection,
            UUID player,
            UUID counterparty,
            long amount,
            LedgerEntryType type,
            long createdAt
    ) throws SQLException {
        String sql = """
                INSERT INTO ledger_entries(player_uuid, counterparty_uuid, amount_cents, entry_type, created_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            if (counterparty == null) {
                statement.setNull(2, java.sql.Types.VARCHAR);
            } else {
                statement.setString(2, counterparty.toString());
            }
            statement.setLong(3, amount);
            statement.setString(4, type.name());
            statement.setLong(5, createdAt);
            statement.executeUpdate();
        }
    }
}
