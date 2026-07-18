package dev.opencivitas.property;

import dev.opencivitas.database.Database;
import dev.opencivitas.economy.LedgerEntryType;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class PropertyRepository {
    private static final String PROPERTY_SELECT = """
            SELECT property.*,
                   titleholder.last_name AS titleholder_name,
                   tenant.last_name AS tenant_name
            FROM properties property
            LEFT JOIN players titleholder ON titleholder.uuid = property.titleholder_uuid
            LEFT JOIN players tenant ON tenant.uuid = property.tenant_uuid
            """;

    private final Database database;

    public PropertyRepository(Database database) {
        this.database = database;
    }

    public List<Property> loadAll() throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(PROPERTY_SELECT + " ORDER BY property.plot_id")) {
            List<Property> properties = new ArrayList<>();
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    properties.add(readProperty(results, Set.of()));
                }
            }
            Map<Long, Set<UUID>> trusted = loadTrust(connection);
            return properties.stream()
                    .map(property -> withTrust(property, trusted.getOrDefault(property.id(), Set.of())))
                    .toList();
        }
    }

    public PropertyOperation create(PropertyDraft draft, long now) throws SQLException {
        validateDraft(draft);
        Bounds bounds = bounds(draft);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (find(connection, draft.plotId()).isPresent()) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.PLOT_ID_TAKEN);
                }
                if (overlaps(connection, draft.worldName(), bounds)) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.OVERLAP);
                }
                long id = insertProperty(connection, draft, bounds, now);
                Property property = find(connection, id).orElseThrow(
                        () -> new SQLException("Created property could not be loaded"));
                connection.commit();
                return PropertyOperation.succeeded(property, 0, 0);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public PropertyOperation delete(String plotId) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Property> selected = find(connection, plotId);
                if (selected.isEmpty()) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.PROPERTY_NOT_FOUND);
                }
                if (selected.get().titleholderId() != null || selected.get().tenantId() != null
                        || hasTransactions(connection, selected.get().id())) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.OCCUPIED);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM properties WHERE id = ?")) {
                    statement.setLong(1, selected.get().id());
                    statement.executeUpdate();
                }
                connection.commit();
                return PropertyOperation.succeeded(null, 0, 0);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public Optional<Property> find(String plotId) throws SQLException {
        try (Connection connection = database.openConnection()) {
            return find(connection, plotId);
        }
    }

    public PropertyOperation buy(UUID buyer, String plotId, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Property> selected = find(connection, plotId);
                if (selected.isEmpty()) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.PROPERTY_NOT_FOUND);
                }
                Property property = selected.get();
                if (property.salePriceCents() == null) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.NOT_FOR_SALE);
                }
                if (!property.availableToBuy()) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.OCCUPIED);
                }
                if (!accountExists(connection, buyer)) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.CITIZEN_NOT_FOUND);
                }
                long price = property.salePriceCents();
                if (!debitPlayer(connection, buyer, price)) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.INSUFFICIENT_FUNDS);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE properties SET titleholder_uuid = ?, updated_at = ? WHERE id = ?
                        """)) {
                    statement.setString(1, buyer.toString());
                    statement.setLong(2, now);
                    statement.setLong(3, property.id());
                    statement.executeUpdate();
                }
                insertPlayerLedger(
                        connection, buyer, null, -price, LedgerEntryType.PROPERTY_PURCHASE, now);
                insertPropertyTransaction(connection, property.id(), buyer, null, "PURCHASE", price, now);
                Property updated = find(connection, property.id()).orElseThrow();
                long balance = playerBalance(connection, buyer);
                connection.commit();
                return PropertyOperation.succeeded(updated, price, balance);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public PropertyOperation rent(UUID renter, String plotId, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Property> selected = find(connection, plotId);
                if (selected.isEmpty()) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.PROPERTY_NOT_FOUND);
                }
                Property property = selected.get();
                if (property.rentPriceCents() == null) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.NOT_FOR_RENT);
                }
                if (property.tenantId() != null) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.OCCUPIED);
                }
                if (renter.equals(property.titleholderId())) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.SELF);
                }
                if (!accountExists(connection, renter)) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.CITIZEN_NOT_FOUND);
                }
                long price = property.rentPriceCents();
                if (!debitPlayer(connection, renter, price)) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.INSUFFICIENT_FUNDS);
                }
                long endsAt = Math.addExact(now, property.rentDurationMillis());
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE properties
                        SET tenant_uuid = ?, rental_started_at = ?, rental_ends_at = ?,
                            rent_paid_cents = ?, updated_at = ?
                        WHERE id = ?
                        """)) {
                    statement.setString(1, renter.toString());
                    statement.setLong(2, now);
                    statement.setLong(3, endsAt);
                    statement.setLong(4, price);
                    statement.setLong(5, now);
                    statement.setLong(6, property.id());
                    statement.executeUpdate();
                }
                clearTrust(connection, property.id());
                insertPlayerLedger(
                        connection, renter, property.titleholderId(), -price, LedgerEntryType.PROPERTY_RENT, now);
                insertPropertyTransaction(
                        connection, property.id(), renter, property.titleholderId(), "RENT", price, now);
                Property updated = find(connection, property.id()).orElseThrow();
                long balance = playerBalance(connection, renter);
                connection.commit();
                return PropertyOperation.succeeded(updated, price, balance);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public PropertyOperation unrent(UUID actor, String plotId, boolean administrator, long now)
            throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Property> selected = find(connection, plotId);
                if (selected.isEmpty()) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.PROPERTY_NOT_FOUND);
                }
                Property property = selected.get();
                if (property.tenantId() == null) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.NOT_RENTED);
                }
                if (!administrator && !property.tenantId().equals(actor)) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.NO_PERMISSION);
                }
                Settlement settlement = rentalSettlement(property, now);
                settleRentalMoney(connection, property, settlement, now);
                clearTenant(connection, property.id(), now);
                clearTrust(connection, property.id());
                insertPropertyTransaction(
                        connection, property.id(), actor, property.tenantId(),
                        "UNRENT", settlement.refundCents(), now);
                Property updated = find(connection, property.id()).orElseThrow();
                long balance = playerBalance(connection, property.tenantId());
                connection.commit();
                return PropertyOperation.succeeded(updated, settlement.refundCents(), balance);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<Property> expireRentals(long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                List<Property> expired = new ArrayList<>();
                String sql = PROPERTY_SELECT + """
                        WHERE property.rental_ends_at IS NOT NULL AND property.rental_ends_at <= ?
                        ORDER BY property.rental_ends_at, property.id
                        """;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setLong(1, now);
                    try (ResultSet results = statement.executeQuery()) {
                        while (results.next()) {
                            expired.add(readProperty(results, Set.of()));
                        }
                    }
                }
                for (Property property : expired) {
                    Settlement settlement = new Settlement(0, property.rentPaidCents());
                    settleRentalMoney(connection, property, settlement, now);
                    clearTenant(connection, property.id(), now);
                    clearTrust(connection, property.id());
                    insertPropertyTransaction(
                            connection, property.id(), null, property.tenantId(),
                            "RENT_EXPIRED", property.rentPaidCents(), now);
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
        return loadAll();
    }

    public PropertyOperation setTitleholder(
            UUID actor, boolean administrator, String plotId, UUID target, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Property> selected = find(connection, plotId);
                if (selected.isEmpty()) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.PROPERTY_NOT_FOUND);
                }
                Property property = selected.get();
                if (!administrator && !actor.equals(property.titleholderId())) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.NO_PERMISSION);
                }
                if (!accountExists(connection, target)) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.CITIZEN_NOT_FOUND);
                }
                if (target.equals(property.titleholderId())) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.SELF);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE properties SET titleholder_uuid = ?, updated_at = ? WHERE id = ?
                        """)) {
                    statement.setString(1, target.toString());
                    statement.setLong(2, now);
                    statement.setLong(3, property.id());
                    statement.executeUpdate();
                }
                clearTrust(connection, property.id());
                insertPropertyTransaction(
                        connection, property.id(), actor, target, "TITLEHOLDER", 0, now);
                Property updated = find(connection, property.id()).orElseThrow();
                connection.commit();
                return PropertyOperation.succeeded(updated, 0, playerBalance(connection, target));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public PropertyOperation setTenant(
            UUID actor, boolean administrator, String plotId, UUID target, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Property> selected = find(connection, plotId);
                if (selected.isEmpty()) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.PROPERTY_NOT_FOUND);
                }
                Property property = selected.get();
                if (!administrator && !actor.equals(property.titleholderId())) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.NO_PERMISSION);
                }
                if (property.tenantId() != null) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.OCCUPIED);
                }
                if (!accountExists(connection, target)) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.CITIZEN_NOT_FOUND);
                }
                if (target.equals(property.titleholderId())) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.SELF);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE properties SET tenant_uuid = ?, updated_at = ? WHERE id = ?
                        """)) {
                    statement.setString(1, target.toString());
                    statement.setLong(2, now);
                    statement.setLong(3, property.id());
                    statement.executeUpdate();
                }
                clearTrust(connection, property.id());
                insertPropertyTransaction(connection, property.id(), actor, target, "TENANT", 0, now);
                Property updated = find(connection, property.id()).orElseThrow();
                connection.commit();
                return PropertyOperation.succeeded(updated, 0, playerBalance(connection, target));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public PropertyOperation trust(
            UUID actor, boolean administrator, String plotId, UUID target, boolean add, long now)
            throws SQLException {
        if (actor != null && actor.equals(target)) {
            return PropertyOperation.failed(PropertyResult.SELF);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Property> selected = find(connection, plotId);
                if (selected.isEmpty()) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.PROPERTY_NOT_FOUND);
                }
                Property property = selected.get();
                if (!administrator && !property.canManage(actor)) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.NO_PERMISSION);
                }
                if (!accountExists(connection, target)) {
                    connection.rollback();
                    return PropertyOperation.failed(PropertyResult.CITIZEN_NOT_FOUND);
                }
                int changed;
                if (add) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO property_trust(property_id, player_uuid, added_at) VALUES (?, ?, ?)
                            ON CONFLICT(property_id, player_uuid) DO NOTHING
                            """)) {
                        statement.setLong(1, property.id());
                        statement.setString(2, target.toString());
                        statement.setLong(3, now);
                        changed = statement.executeUpdate();
                    }
                } else {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM property_trust WHERE property_id = ? AND player_uuid = ?")) {
                        statement.setLong(1, property.id());
                        statement.setString(2, target.toString());
                        changed = statement.executeUpdate();
                    }
                }
                if (changed == 0) {
                    connection.rollback();
                    return PropertyOperation.failed(add
                            ? PropertyResult.ALREADY_TRUSTED : PropertyResult.NOT_TRUSTED);
                }
                Property updated = find(connection, property.id()).orElseThrow();
                connection.commit();
                return PropertyOperation.succeeded(updated, 0,
                        actor == null ? 0 : playerBalance(connection, actor));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<Property> searchRentals(long minimumCents, long maximumCents, int limit) throws SQLException {
        String sql = PROPERTY_SELECT + """
                WHERE property.rent_price_cents BETWEEN ? AND ? AND property.tenant_uuid IS NULL
                ORDER BY property.rent_price_cents, property.plot_id COLLATE NOCASE
                LIMIT ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, minimumCents);
            statement.setLong(2, maximumCents);
            statement.setInt(3, limit);
            List<Property> properties = new ArrayList<>();
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    properties.add(readProperty(results, Set.of()));
                }
            }
            return List.copyOf(properties);
        }
    }

    private static Optional<Property> find(Connection connection, String plotId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                PROPERTY_SELECT + " WHERE property.plot_id = ? COLLATE NOCASE")) {
            statement.setString(1, plotId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next()
                        ? Optional.of(readProperty(results, trust(connection, results.getLong("id"))))
                        : Optional.empty();
            }
        }
    }

    private static Optional<Property> find(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                PROPERTY_SELECT + " WHERE property.id = ?")) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next()
                        ? Optional.of(readProperty(results, trust(connection, id)))
                        : Optional.empty();
            }
        }
    }

    private static Property readProperty(ResultSet results, Set<UUID> trusted) throws SQLException {
        long sale = results.getLong("sale_price_cents");
        Long salePrice = results.wasNull() ? null : sale;
        long rent = results.getLong("rent_price_cents");
        Long rentPrice = results.wasNull() ? null : rent;
        String titleholder = results.getString("titleholder_uuid");
        String tenant = results.getString("tenant_uuid");
        long started = results.getLong("rental_started_at");
        Instant rentalStarted = results.wasNull() ? null : Instant.ofEpochMilli(started);
        long ends = results.getLong("rental_ends_at");
        Instant rentalEnds = results.wasNull() ? null : Instant.ofEpochMilli(ends);
        return new Property(
                results.getLong("id"), results.getString("plot_id"), results.getString("world_name"),
                results.getInt("min_x"), results.getInt("max_x"),
                results.getInt("min_y"), results.getInt("max_y"),
                results.getInt("min_z"), results.getInt("max_z"),
                salePrice, rentPrice, results.getLong("rent_duration_millis"),
                titleholder == null ? null : UUID.fromString(titleholder),
                results.getString("titleholder_name"),
                tenant == null ? null : UUID.fromString(tenant),
                results.getString("tenant_name"),
                rentalStarted, rentalEnds, results.getLong("rent_paid_cents"),
                trusted, Instant.ofEpochMilli(results.getLong("created_at")));
    }

    private static Property withTrust(Property property, Set<UUID> trusted) {
        return new Property(
                property.id(), property.plotId(), property.worldName(),
                property.minX(), property.maxX(), property.minY(), property.maxY(),
                property.minZ(), property.maxZ(), property.salePriceCents(), property.rentPriceCents(),
                property.rentDurationMillis(), property.titleholderId(), property.titleholderName(),
                property.tenantId(), property.tenantName(), property.rentalStartedAt(), property.rentalEndsAt(),
                property.rentPaidCents(), trusted, property.createdAt());
    }

    private static long insertProperty(Connection connection, PropertyDraft draft, Bounds bounds, long now)
            throws SQLException {
        String sql = """
                INSERT INTO properties(
                    plot_id, world_name, min_x, max_x, min_y, max_y, min_z, max_z,
                    sale_price_cents, rent_price_cents, rent_duration_millis, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, draft.plotId());
            statement.setString(2, draft.worldName());
            statement.setInt(3, bounds.minX());
            statement.setInt(4, bounds.maxX());
            statement.setInt(5, bounds.minY());
            statement.setInt(6, bounds.maxY());
            statement.setInt(7, bounds.minZ());
            statement.setInt(8, bounds.maxZ());
            setNullableLong(statement, 9, draft.salePriceCents());
            setNullableLong(statement, 10, draft.rentPriceCents());
            statement.setLong(11, draft.rentDurationMillis());
            statement.setLong(12, now);
            statement.setLong(13, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Property insert did not return an id");
                }
                return keys.getLong(1);
            }
        }
    }

    private static Settlement rentalSettlement(Property property, long now) {
        if (property.rentPaidCents() == 0
                || property.rentalStartedAt() == null || property.rentalEndsAt() == null) {
            return new Settlement(0, 0);
        }
        long duration = property.rentalEndsAt().toEpochMilli() - property.rentalStartedAt().toEpochMilli();
        long remaining = Math.min(
                duration, Math.max(0, property.rentalEndsAt().toEpochMilli() - now));
        long refund = BigInteger.valueOf(property.rentPaidCents())
                .multiply(BigInteger.valueOf(remaining))
                .divide(BigInteger.valueOf(duration))
                .longValueExact();
        return new Settlement(refund, property.rentPaidCents() - refund);
    }

    private static void settleRentalMoney(
            Connection connection, Property property, Settlement settlement, long now) throws SQLException {
        if (settlement.refundCents() > 0) {
            creditPlayer(connection, property.tenantId(), settlement.refundCents());
            insertPlayerLedger(
                    connection, property.tenantId(), property.titleholderId(), settlement.refundCents(),
                    LedgerEntryType.PROPERTY_RENT_REFUND, now);
        }
        if (settlement.landlordCents() > 0 && property.titleholderId() != null) {
            creditPlayer(connection, property.titleholderId(), settlement.landlordCents());
            insertPlayerLedger(
                    connection, property.titleholderId(), property.tenantId(), settlement.landlordCents(),
                    LedgerEntryType.PROPERTY_RENT_INCOME, now);
        }
    }

    private static void clearTenant(Connection connection, long propertyId, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE properties
                SET tenant_uuid = NULL, rental_started_at = NULL, rental_ends_at = NULL,
                    rent_paid_cents = 0, updated_at = ?
                WHERE id = ?
                """)) {
            statement.setLong(1, now);
            statement.setLong(2, propertyId);
            statement.executeUpdate();
        }
    }

    private static void clearTrust(Connection connection, long propertyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM property_trust WHERE property_id = ?")) {
            statement.setLong(1, propertyId);
            statement.executeUpdate();
        }
    }

    private static Map<Long, Set<UUID>> loadTrust(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT property_id, player_uuid FROM property_trust")) {
            try (ResultSet results = statement.executeQuery()) {
                Map<Long, Set<UUID>> trusted = new HashMap<>();
                while (results.next()) {
                    trusted.computeIfAbsent(results.getLong(1), ignored -> new HashSet<>())
                            .add(UUID.fromString(results.getString(2)));
                }
                return trusted;
            }
        }
    }

    private static Set<UUID> trust(Connection connection, long propertyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid FROM property_trust WHERE property_id = ?")) {
            statement.setLong(1, propertyId);
            try (ResultSet results = statement.executeQuery()) {
                Set<UUID> trusted = new HashSet<>();
                while (results.next()) {
                    trusted.add(UUID.fromString(results.getString(1)));
                }
                return Set.copyOf(trusted);
            }
        }
    }

    private static boolean overlaps(Connection connection, String world, Bounds bounds) throws SQLException {
        String sql = """
                SELECT 1 FROM properties
                WHERE world_name = ? AND NOT (
                    max_x < ? OR min_x > ? OR max_y < ? OR min_y > ? OR max_z < ? OR min_z > ?
                ) LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, world);
            statement.setInt(2, bounds.minX());
            statement.setInt(3, bounds.maxX());
            statement.setInt(4, bounds.minY());
            statement.setInt(5, bounds.maxY());
            statement.setInt(6, bounds.minZ());
            statement.setInt(7, bounds.maxZ());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static boolean accountExists(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM accounts WHERE player_uuid = ?")) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static boolean hasTransactions(Connection connection, long propertyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM property_transactions WHERE property_id = ? LIMIT 1")) {
            statement.setLong(1, propertyId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static boolean debitPlayer(Connection connection, UUID player, long amount) throws SQLException {
        String sql = """
                UPDATE accounts SET balance_cents = balance_cents - ?
                WHERE player_uuid = ? AND balance_cents >= ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, amount);
            statement.setString(2, player.toString());
            statement.setLong(3, amount);
            return statement.executeUpdate() == 1;
        }
    }

    private static void creditPlayer(Connection connection, UUID player, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance_cents = balance_cents + ? WHERE player_uuid = ?")) {
            statement.setLong(1, amount);
            statement.setString(2, player.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Property settlement account not found");
            }
        }
    }

    private static long playerBalance(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_cents FROM accounts WHERE player_uuid = ?")) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("Property account not found");
                }
                return results.getLong(1);
            }
        }
    }

    private static void insertPlayerLedger(
            Connection connection,
            UUID player,
            UUID counterparty,
            long amount,
            LedgerEntryType type,
            long now
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
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private static void insertPropertyTransaction(
            Connection connection,
            long propertyId,
            UUID actor,
            UUID counterparty,
            String type,
            long amount,
            long now
    ) throws SQLException {
        String sql = """
                INSERT INTO property_transactions(
                    property_id, actor_uuid, counterparty_uuid, transaction_type, amount_cents, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, propertyId);
            setNullableUuid(statement, 2, actor);
            setNullableUuid(statement, 3, counterparty);
            statement.setString(4, type);
            statement.setLong(5, amount);
            statement.setLong(6, now);
            statement.executeUpdate();
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setNullableUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, value.toString());
        }
    }

    private static void validateDraft(PropertyDraft draft) {
        if (draft.plotId() == null || !draft.plotId().matches("[a-z0-9][a-z0-9-]{0,31}")
                || draft.worldName() == null || draft.worldName().isBlank()
                || (draft.salePriceCents() == null && draft.rentPriceCents() == null)
                || (draft.salePriceCents() != null && draft.salePriceCents() <= 0)
                || (draft.rentPriceCents() != null && draft.rentPriceCents() <= 0)
                || draft.rentDurationMillis() <= 0) {
            throw new IllegalArgumentException("Invalid property draft");
        }
    }

    private static Bounds bounds(PropertyDraft draft) {
        return new Bounds(
                Math.min(draft.firstX(), draft.secondX()), Math.max(draft.firstX(), draft.secondX()),
                Math.min(draft.firstY(), draft.secondY()), Math.max(draft.firstY(), draft.secondY()),
                Math.min(draft.firstZ(), draft.secondZ()), Math.max(draft.firstZ(), draft.secondZ()));
    }

    private record Bounds(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
    }

    private record Settlement(long refundCents, long landlordCents) {
    }
}
