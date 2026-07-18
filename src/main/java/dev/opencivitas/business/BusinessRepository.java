package dev.opencivitas.business;

import dev.opencivitas.database.Database;
import dev.opencivitas.economy.LedgerEntryType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class BusinessRepository {
    private final Database database;

    public BusinessRepository(Database database) {
        this.database = database;
    }

    public BusinessResult create(UUID proprietor, String slug, String displayName) throws SQLException {
        long now = Instant.now().toEpochMilli();
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!citizenExists(connection, proprietor)) {
                    connection.rollback();
                    return BusinessResult.CITIZEN_NOT_FOUND;
                }
                if (!hasQualification(connection, proprietor, "entrepreneur")) {
                    connection.rollback();
                    return BusinessResult.MISSING_QUALIFICATION;
                }
                if (businessId(connection, slug).isPresent()) {
                    connection.rollback();
                    return BusinessResult.NAME_TAKEN;
                }
                long businessId = insertBusiness(connection, proprietor, slug, displayName, now);
                insertMember(connection, businessId, proprietor, BusinessRole.PROPRIETOR, now);
                connection.commit();
                return BusinessResult.SUCCESS;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public Optional<Business> find(String slug) throws SQLException {
        String sql = """
                SELECT b.id, b.slug, b.display_name, b.proprietor_uuid, p.last_name AS proprietor_name,
                       b.balance_cents, b.status, b.created_at
                FROM businesses b
                JOIN players p ON p.uuid = b.proprietor_uuid
                WHERE b.slug = ? COLLATE NOCASE
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, slug);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readBusiness(results)) : Optional.empty();
            }
        }
    }

    public List<Business> list(UUID member) throws SQLException {
        String sql = """
                SELECT b.id, b.slug, b.display_name, b.proprietor_uuid, p.last_name AS proprietor_name,
                       b.balance_cents, b.status, b.created_at
                FROM businesses b
                JOIN business_members m ON m.business_id = b.id
                JOIN players p ON p.uuid = b.proprietor_uuid
                WHERE m.player_uuid = ?
                ORDER BY b.display_name COLLATE NOCASE
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, member.toString());
            return readBusinesses(statement);
        }
    }

    public List<Business> listAll(int limit, int offset) throws SQLException {
        String sql = """
                SELECT b.id, b.slug, b.display_name, b.proprietor_uuid, p.last_name AS proprietor_name,
                       b.balance_cents, b.status, b.created_at
                FROM businesses b
                JOIN players p ON p.uuid = b.proprietor_uuid
                WHERE b.status = 'ACTIVE'
                ORDER BY b.display_name COLLATE NOCASE
                LIMIT ? OFFSET ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            statement.setInt(2, offset);
            return readBusinesses(statement);
        }
    }

    public Optional<BusinessRole> role(String slug, UUID player) throws SQLException {
        String sql = """
                SELECT m.role
                FROM business_members m
                JOIN businesses b ON b.id = m.business_id
                WHERE b.slug = ? COLLATE NOCASE AND m.player_uuid = ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, slug);
            statement.setString(2, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next()
                        ? Optional.of(BusinessRole.valueOf(results.getString(1)))
                        : Optional.empty();
            }
        }
    }

    public BusinessOperation deposit(UUID actor, String slug, long amount) throws SQLException {
        validateAmount(amount);
        return transfer(actor, null, slug, amount, TransferKind.DEPOSIT);
    }

    public BusinessOperation withdraw(UUID actor, String slug, long amount) throws SQLException {
        validateAmount(amount);
        return transfer(actor, actor, slug, amount, TransferKind.WITHDRAWAL);
    }

    public BusinessOperation pay(UUID actor, String slug, UUID recipient, long amount) throws SQLException {
        validateAmount(amount);
        return transfer(actor, recipient, slug, amount, TransferKind.PAYMENT);
    }

    public BusinessOperation disband(UUID actor, String slug) throws SQLException {
        long now = Instant.now().toEpochMilli();
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Long> id = businessId(connection, slug);
                if (id.isEmpty()) {
                    connection.rollback();
                    return new BusinessOperation(BusinessResult.BUSINESS_NOT_FOUND, 0);
                }
                BusinessRow business = businessRow(connection, id.get());
                if (business.status() != BusinessStatus.ACTIVE) {
                    connection.rollback();
                    return new BusinessOperation(BusinessResult.BUSINESS_INACTIVE, business.balance());
                }
                if (!business.proprietor().equals(actor)) {
                    connection.rollback();
                    return new BusinessOperation(BusinessResult.NO_PERMISSION, business.balance());
                }
                if (business.balance() > 0) {
                    depositPlayer(connection, actor, business.balance());
                    insertPlayerLedger(connection, actor, business.balance(), LedgerEntryType.BUSINESS_TRANSFER, now);
                    insertBusinessLedger(
                            connection, id.get(), actor, actor, -business.balance(), "DISBAND_REFUND", now);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE businesses SET balance_cents = 0, status = 'DISBANDED', disbanded_at = ? WHERE id = ?")) {
                    statement.setLong(1, now);
                    statement.setLong(2, id.get());
                    statement.executeUpdate();
                }
                connection.commit();
                return new BusinessOperation(BusinessResult.SUCCESS, 0);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<BusinessLedgerEntry> ledger(String slug, int limit, int offset) throws SQLException {
        String sql = """
                SELECT l.id, l.amount_cents, l.entry_type, l.created_at,
                       actor.last_name AS actor_name, counterparty.last_name AS counterparty_name
                FROM business_ledger_entries l
                JOIN businesses b ON b.id = l.business_id
                LEFT JOIN players actor ON actor.uuid = l.actor_uuid
                LEFT JOIN players counterparty ON counterparty.uuid = l.counterparty_uuid
                WHERE b.slug = ? COLLATE NOCASE
                ORDER BY l.created_at DESC, l.id DESC
                LIMIT ? OFFSET ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, slug);
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            try (ResultSet results = statement.executeQuery()) {
                List<BusinessLedgerEntry> entries = new ArrayList<>();
                while (results.next()) {
                    entries.add(new BusinessLedgerEntry(
                            results.getLong("id"),
                            results.getLong("amount_cents"),
                            results.getString("entry_type"),
                            results.getString("actor_name"),
                            results.getString("counterparty_name"),
                            Instant.ofEpochMilli(results.getLong("created_at"))
                    ));
                }
                return List.copyOf(entries);
            }
        }
    }

    private BusinessOperation transfer(
            UUID actor,
            UUID recipient,
            String slug,
            long amount,
            TransferKind kind
    ) throws SQLException {
        long now = Instant.now().toEpochMilli();
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Long> id = businessId(connection, slug);
                if (id.isEmpty()) {
                    connection.rollback();
                    return new BusinessOperation(BusinessResult.BUSINESS_NOT_FOUND, 0);
                }
                BusinessRow business = businessRow(connection, id.get());
                if (business.status() != BusinessStatus.ACTIVE) {
                    connection.rollback();
                    return new BusinessOperation(BusinessResult.BUSINESS_INACTIVE, business.balance());
                }
                if (kind != TransferKind.DEPOSIT && !canManageFunds(connection, id.get(), actor)) {
                    connection.rollback();
                    return new BusinessOperation(BusinessResult.NO_PERMISSION, business.balance());
                }
                if (kind == TransferKind.DEPOSIT) {
                    if (withdrawPlayer(connection, actor, amount) == 0) {
                        connection.rollback();
                        return new BusinessOperation(
                                citizenExists(connection, actor)
                                        ? BusinessResult.INSUFFICIENT_PERSONAL_FUNDS
                                        : BusinessResult.CITIZEN_NOT_FOUND,
                                business.balance()
                        );
                    }
                    updateBusinessBalance(connection, id.get(), amount, false);
                    insertPlayerLedger(connection, actor, -amount, LedgerEntryType.BUSINESS_TRANSFER, now);
                    insertBusinessLedger(connection, id.get(), actor, actor, amount, "DEPOSIT", now);
                } else {
                    if (recipient == null || !citizenExists(connection, recipient)) {
                        connection.rollback();
                        return new BusinessOperation(BusinessResult.CITIZEN_NOT_FOUND, business.balance());
                    }
                    if (updateBusinessBalance(connection, id.get(), -amount, true) == 0) {
                        connection.rollback();
                        return new BusinessOperation(BusinessResult.INSUFFICIENT_BUSINESS_FUNDS, business.balance());
                    }
                    depositPlayer(connection, recipient, amount);
                    LedgerEntryType type = kind == TransferKind.PAYMENT
                            ? LedgerEntryType.BUSINESS_PAYMENT : LedgerEntryType.BUSINESS_TRANSFER;
                    insertPlayerLedger(connection, recipient, amount, type, now);
                    insertBusinessLedger(
                            connection,
                            id.get(),
                            actor,
                            recipient,
                            -amount,
                            kind == TransferKind.PAYMENT ? "PAYMENT" : "WITHDRAWAL",
                            now
                    );
                }
                long balance = businessBalance(connection, id.get());
                connection.commit();
                return new BusinessOperation(BusinessResult.SUCCESS, balance);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static Business readBusiness(ResultSet results) throws SQLException {
        return new Business(
                results.getLong("id"),
                results.getString("slug"),
                results.getString("display_name"),
                UUID.fromString(results.getString("proprietor_uuid")),
                results.getString("proprietor_name"),
                results.getLong("balance_cents"),
                BusinessStatus.valueOf(results.getString("status")),
                Instant.ofEpochMilli(results.getLong("created_at"))
        );
    }

    private static List<Business> readBusinesses(PreparedStatement statement) throws SQLException {
        try (ResultSet results = statement.executeQuery()) {
            List<Business> businesses = new ArrayList<>();
            while (results.next()) {
                businesses.add(readBusiness(results));
            }
            return List.copyOf(businesses);
        }
    }

    private static long insertBusiness(
            Connection connection,
            UUID proprietor,
            String slug,
            String displayName,
            long now
    ) throws SQLException {
        String sql = """
                INSERT INTO businesses(slug, display_name, proprietor_uuid, created_at)
                VALUES (?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, slug);
            statement.setString(2, displayName);
            statement.setString(3, proprietor.toString());
            statement.setLong(4, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Business insert did not return an id");
                }
                return keys.getLong(1);
            }
        }
    }

    private static void insertMember(
            Connection connection,
            long businessId,
            UUID player,
            BusinessRole role,
            long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO business_members(business_id, player_uuid, role, joined_at) VALUES (?, ?, ?, ?)")) {
            statement.setLong(1, businessId);
            statement.setString(2, player.toString());
            statement.setString(3, role.name());
            statement.setLong(4, now);
            statement.executeUpdate();
        }
    }

    private static Optional<Long> businessId(Connection connection, String slug) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM businesses WHERE slug = ? COLLATE NOCASE")) {
            statement.setString(1, slug);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(results.getLong(1)) : Optional.empty();
            }
        }
    }

    private static BusinessRow businessRow(Connection connection, long businessId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT proprietor_uuid, balance_cents, status FROM businesses WHERE id = ?")) {
            statement.setLong(1, businessId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("Business disappeared during transaction");
                }
                return new BusinessRow(
                        UUID.fromString(results.getString(1)),
                        results.getLong(2),
                        BusinessStatus.valueOf(results.getString(3))
                );
            }
        }
    }

    private static boolean citizenExists(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE uuid = ?")) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static boolean hasQualification(Connection connection, UUID player, String qualification)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM qualifications WHERE player_uuid = ? AND qualification_id = ?")) {
            statement.setString(1, player.toString());
            statement.setString(2, qualification);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static boolean canManageFunds(Connection connection, long businessId, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role FROM business_members WHERE business_id = ? AND player_uuid = ?")) {
            statement.setLong(1, businessId);
            statement.setString(2, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() && BusinessRole.valueOf(results.getString(1)).canManageFunds();
            }
        }
    }

    private static int withdrawPlayer(Connection connection, UUID player, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance_cents = balance_cents - ? "
                        + "WHERE player_uuid = ? AND balance_cents >= ?")) {
            statement.setLong(1, amount);
            statement.setString(2, player.toString());
            statement.setLong(3, amount);
            return statement.executeUpdate();
        }
    }

    private static void depositPlayer(Connection connection, UUID player, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance_cents = balance_cents + ? WHERE player_uuid = ?")) {
            statement.setLong(1, amount);
            statement.setString(2, player.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Player account not found");
            }
        }
    }

    private static int updateBusinessBalance(
            Connection connection,
            long businessId,
            long delta,
            boolean checkFunds
    ) throws SQLException {
        String sql = checkFunds
                ? "UPDATE businesses SET balance_cents = balance_cents + ? WHERE id = ? AND balance_cents >= ?"
                : "UPDATE businesses SET balance_cents = balance_cents + ? WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, delta);
            statement.setLong(2, businessId);
            if (checkFunds) {
                statement.setLong(3, -delta);
            }
            return statement.executeUpdate();
        }
    }

    private static long businessBalance(Connection connection, long businessId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_cents FROM businesses WHERE id = ?")) {
            statement.setLong(1, businessId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("Business account not found");
                }
                return results.getLong(1);
            }
        }
    }

    private static void insertPlayerLedger(
            Connection connection,
            UUID player,
            long amount,
            LedgerEntryType type,
            long now
    ) throws SQLException {
        String sql = """
                INSERT INTO ledger_entries(player_uuid, counterparty_uuid, amount_cents, entry_type, created_at)
                VALUES (?, NULL, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            statement.setLong(2, amount);
            statement.setString(3, type.name());
            statement.setLong(4, now);
            statement.executeUpdate();
        }
    }

    private static void insertBusinessLedger(
            Connection connection,
            long businessId,
            UUID actor,
            UUID counterparty,
            long amount,
            String type,
            long now
    ) throws SQLException {
        String sql = """
                INSERT INTO business_ledger_entries(
                    business_id, actor_uuid, counterparty_uuid, amount_cents, entry_type, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, businessId);
            statement.setString(2, actor.toString());
            statement.setString(3, counterparty.toString());
            statement.setLong(4, amount);
            statement.setString(5, type);
            statement.setLong(6, now);
            statement.executeUpdate();
        }
    }

    private static void validateAmount(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Business transfer amount must be positive");
        }
    }

    private enum TransferKind {
        DEPOSIT,
        WITHDRAWAL,
        PAYMENT
    }

    private record BusinessRow(UUID proprietor, long balance, BusinessStatus status) {
    }
}
