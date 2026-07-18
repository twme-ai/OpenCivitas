package dev.opencivitas.claim;

import dev.opencivitas.database.Database;
import dev.opencivitas.economy.LedgerEntryType;

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

public final class ClaimRepository {
    private static final String CLAIM_SELECT = """
            SELECT c.*, p.last_name AS owner_name
            FROM wilderness_claims c
            JOIN players p ON p.uuid = c.owner_uuid
            """;

    private final Database database;
    private final int freeBlocks;
    private final int maximumBlocks;
    private final long blockCostCents;

    public ClaimRepository(Database database, int freeBlocks, int maximumBlocks, long blockCostCents) {
        if (freeBlocks < 0 || maximumBlocks < 1 || freeBlocks > maximumBlocks || blockCostCents < 1) {
            throw new IllegalArgumentException("Invalid claim economy settings");
        }
        this.database = database;
        this.freeBlocks = freeBlocks;
        this.maximumBlocks = maximumBlocks;
        this.blockCostCents = blockCostCents;
    }

    public List<LandClaim> loadAll() throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(CLAIM_SELECT + " ORDER BY c.id")) {
            List<LandClaim> claims = new ArrayList<>();
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    claims.add(readClaim(results, Set.of()));
                }
            }
            Map<Long, Set<UUID>> trust = loadTrust(connection);
            return claims.stream()
                    .map(claim -> withTrust(claim, trust.getOrDefault(claim.id(), Set.of())))
                    .toList();
        }
    }

    public ClaimOperation create(
            UUID owner,
            String world,
            int firstX,
            int firstZ,
            int secondX,
            int secondZ,
            long now
    ) throws SQLException {
        Bounds bounds = bounds(firstX, firstZ, secondX, secondZ);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!accountExists(connection, owner)) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.CITIZEN_NOT_FOUND);
                }
                if (overlaps(connection, world, bounds, null)) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.OVERLAP);
                }
                int used = usedBlocks(connection, owner, null);
                int entitled = entitlement(connection, owner);
                if ((long) used + bounds.area() > maximumBlocks) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.MAX_BLOCKS);
                }
                if ((long) used + bounds.area() > entitled) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.INSUFFICIENT_BLOCKS);
                }
                long id = insertClaim(connection, owner, world, bounds, now);
                LandClaim claim = find(connection, id).orElseThrow(
                        () -> new SQLException("Created claim could not be loaded"));
                connection.commit();
                return ClaimOperation.succeeded(
                        claim, Math.max(0, entitled - used - bounds.area()), playerBalance(connection, owner));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public ClaimOperation resize(
            UUID actor,
            long claimId,
            int fixedX,
            int fixedZ,
            int movedX,
            int movedZ,
            long now
    ) throws SQLException {
        Bounds bounds = bounds(fixedX, fixedZ, movedX, movedZ);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<LandClaim> selected = find(connection, claimId);
                if (selected.isEmpty()) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.CLAIM_NOT_FOUND);
                }
                LandClaim claim = selected.get();
                if (!claim.ownerId().equals(actor)) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.NO_PERMISSION);
                }
                if (overlaps(connection, claim.worldName(), bounds, claimId)) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.OVERLAP);
                }
                int usedWithoutClaim = usedBlocks(connection, actor, claimId);
                int entitled = entitlement(connection, actor);
                if ((long) usedWithoutClaim + bounds.area() > maximumBlocks) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.MAX_BLOCKS);
                }
                if ((long) usedWithoutClaim + bounds.area() > entitled) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.INSUFFICIENT_BLOCKS);
                }
                updateBounds(connection, claimId, bounds, now);
                LandClaim updated = find(connection, claimId).orElseThrow();
                connection.commit();
                return ClaimOperation.succeeded(
                        updated, Math.max(0, entitled - usedWithoutClaim - bounds.area()),
                        playerBalance(connection, actor));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public ClaimOperation purchaseBlocks(UUID player, int blocks, long now) throws SQLException {
        if (blocks < 1) {
            throw new IllegalArgumentException("Claim block purchase must be positive");
        }
        long cost;
        try {
            cost = Math.multiplyExact(blockCostCents, blocks);
        } catch (ArithmeticException exception) {
            return ClaimOperation.failed(ClaimResult.MAX_BLOCKS);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!accountExists(connection, player)) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.CITIZEN_NOT_FOUND);
                }
                int purchased = purchasedBlocks(connection, player);
                if ((long) freeBlocks + purchased + blocks > maximumBlocks) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.MAX_BLOCKS);
                }
                if (!debitPlayer(connection, player, cost)) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.INSUFFICIENT_FUNDS);
                }
                setPurchasedBlocks(connection, player, purchased + blocks, now);
                insertPlayerLedger(connection, player, -cost, now);
                int used = usedBlocks(connection, player, null);
                long balance = playerBalance(connection, player);
                connection.commit();
                return new ClaimOperation(
                        ClaimResult.SUCCESS, Optional.empty(),
                        Math.max(0, freeBlocks + purchased + blocks - used), balance);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public ClaimOperation delete(UUID actor, long claimId) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<LandClaim> selected = find(connection, claimId);
                if (selected.isEmpty()) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.CLAIM_NOT_FOUND);
                }
                if (!selected.get().ownerId().equals(actor)) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.NO_PERMISSION);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM wilderness_claims WHERE id = ?")) {
                    statement.setLong(1, claimId);
                    statement.executeUpdate();
                }
                int remaining = Math.max(0, entitlement(connection, actor) - usedBlocks(connection, actor, null));
                long balance = playerBalance(connection, actor);
                connection.commit();
                return ClaimOperation.succeeded(null, remaining, balance);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public ClaimOperation transfer(UUID actor, long claimId, UUID recipient, long now) throws SQLException {
        if (actor.equals(recipient)) {
            return ClaimOperation.failed(ClaimResult.SELF);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<LandClaim> selected = find(connection, claimId);
                if (selected.isEmpty()) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.CLAIM_NOT_FOUND);
                }
                LandClaim claim = selected.get();
                if (!claim.ownerId().equals(actor)) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.NO_PERMISSION);
                }
                if (!accountExists(connection, recipient)) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.CITIZEN_NOT_FOUND);
                }
                if ((long) usedBlocks(connection, recipient, null) + claim.area() > maximumBlocks) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.MAX_BLOCKS);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE wilderness_claims SET owner_uuid = ?, updated_at = ? WHERE id = ?
                        """)) {
                    statement.setString(1, recipient.toString());
                    statement.setLong(2, now);
                    statement.setLong(3, claimId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM claim_trust WHERE claim_id = ?")) {
                    statement.setLong(1, claimId);
                    statement.executeUpdate();
                }
                LandClaim updated = find(connection, claimId).orElseThrow();
                int remaining = Math.max(0,
                        entitlement(connection, recipient) - usedBlocks(connection, recipient, null));
                connection.commit();
                return ClaimOperation.succeeded(updated, remaining, playerBalance(connection, recipient));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public ClaimOperation trust(UUID actor, long claimId, UUID target, long now) throws SQLException {
        if (actor.equals(target)) {
            return ClaimOperation.failed(ClaimResult.SELF);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                ClaimResult permission = verifyOwnerAndTarget(connection, actor, claimId, target);
                if (permission != ClaimResult.SUCCESS) {
                    connection.rollback();
                    return ClaimOperation.failed(permission);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO claim_trust(claim_id, player_uuid, added_at) VALUES (?, ?, ?)
                        ON CONFLICT(claim_id, player_uuid) DO NOTHING
                        """)) {
                    statement.setLong(1, claimId);
                    statement.setString(2, target.toString());
                    statement.setLong(3, now);
                    if (statement.executeUpdate() == 0) {
                        connection.rollback();
                        return ClaimOperation.failed(ClaimResult.ALREADY_TRUSTED);
                    }
                }
                LandClaim updated = find(connection, claimId).orElseThrow();
                connection.commit();
                return ClaimOperation.succeeded(updated, 0, playerBalance(connection, actor));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public ClaimOperation untrust(UUID actor, long claimId, UUID target) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<LandClaim> selected = find(connection, claimId);
                if (selected.isEmpty()) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.CLAIM_NOT_FOUND);
                }
                if (!selected.get().ownerId().equals(actor)) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.NO_PERMISSION);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM claim_trust WHERE claim_id = ? AND player_uuid = ?")) {
                    statement.setLong(1, claimId);
                    statement.setString(2, target.toString());
                    if (statement.executeUpdate() == 0) {
                        connection.rollback();
                        return ClaimOperation.failed(ClaimResult.NOT_TRUSTED);
                    }
                }
                LandClaim updated = find(connection, claimId).orElseThrow();
                connection.commit();
                return ClaimOperation.succeeded(updated, 0, playerBalance(connection, actor));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public ClaimOperation toggleExplosions(UUID actor, long claimId, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<LandClaim> selected = find(connection, claimId);
                if (selected.isEmpty()) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.CLAIM_NOT_FOUND);
                }
                if (!selected.get().ownerId().equals(actor)) {
                    connection.rollback();
                    return ClaimOperation.failed(ClaimResult.NO_PERMISSION);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE wilderness_claims SET explosions = CASE explosions WHEN 0 THEN 1 ELSE 0 END,
                            updated_at = ? WHERE id = ?
                        """)) {
                    statement.setLong(1, now);
                    statement.setLong(2, claimId);
                    statement.executeUpdate();
                }
                LandClaim updated = find(connection, claimId).orElseThrow();
                connection.commit();
                return ClaimOperation.succeeded(updated, 0, playerBalance(connection, actor));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public ClaimCapacity capacity(UUID player) throws SQLException {
        try (Connection connection = database.openConnection()) {
            int purchased = purchasedBlocks(connection, player);
            int used = usedBlocks(connection, player, null);
            return new ClaimCapacity(
                    purchased, used, Math.max(0, freeBlocks + purchased - used), maximumBlocks);
        }
    }

    public boolean issueWand(UUID player, String utcDay) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!accountExists(connection, player)) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT issued_day FROM claim_wands WHERE player_uuid = ?")) {
                    statement.setString(1, player.toString());
                    try (ResultSet results = statement.executeQuery()) {
                        if (results.next() && utcDay.equals(results.getString(1))) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO claim_wands(player_uuid, issued_day) VALUES (?, ?)
                        ON CONFLICT(player_uuid) DO UPDATE SET issued_day = excluded.issued_day
                        """)) {
                    statement.setString(1, player.toString());
                    statement.setString(2, utcDay);
                    statement.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static Optional<LandClaim> find(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CLAIM_SELECT + " WHERE c.id = ?")) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                return Optional.of(readClaim(results, trust(connection, id)));
            }
        }
    }

    private static LandClaim readClaim(ResultSet results, Set<UUID> trusted) throws SQLException {
        return new LandClaim(
                results.getLong("id"),
                UUID.fromString(results.getString("owner_uuid")),
                results.getString("owner_name"),
                results.getString("world_name"),
                results.getInt("min_x"), results.getInt("max_x"),
                results.getInt("min_z"), results.getInt("max_z"),
                results.getInt("explosions") == 1,
                trusted,
                Instant.ofEpochMilli(results.getLong("created_at")));
    }

    private static LandClaim withTrust(LandClaim claim, Set<UUID> trusted) {
        return new LandClaim(
                claim.id(), claim.ownerId(), claim.ownerName(), claim.worldName(),
                claim.minX(), claim.maxX(), claim.minZ(), claim.maxZ(), claim.explosions(),
                trusted, claim.createdAt());
    }

    private static Map<Long, Set<UUID>> loadTrust(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT claim_id, player_uuid FROM claim_trust")) {
            try (ResultSet results = statement.executeQuery()) {
                Map<Long, Set<UUID>> trust = new HashMap<>();
                while (results.next()) {
                    trust.computeIfAbsent(results.getLong("claim_id"), ignored -> new HashSet<>())
                            .add(UUID.fromString(results.getString("player_uuid")));
                }
                return trust;
            }
        }
    }

    private static Set<UUID> trust(Connection connection, long claimId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid FROM claim_trust WHERE claim_id = ?")) {
            statement.setLong(1, claimId);
            try (ResultSet results = statement.executeQuery()) {
                Set<UUID> trust = new HashSet<>();
                while (results.next()) {
                    trust.add(UUID.fromString(results.getString(1)));
                }
                return Set.copyOf(trust);
            }
        }
    }

    private static long insertClaim(
            Connection connection, UUID owner, String world, Bounds bounds, long now) throws SQLException {
        String sql = """
                INSERT INTO wilderness_claims(
                    owner_uuid, world_name, min_x, max_x, min_z, max_z, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, owner.toString());
            statement.setString(2, world);
            statement.setInt(3, bounds.minX());
            statement.setInt(4, bounds.maxX());
            statement.setInt(5, bounds.minZ());
            statement.setInt(6, bounds.maxZ());
            statement.setLong(7, now);
            statement.setLong(8, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Claim insert did not return an id");
                }
                return keys.getLong(1);
            }
        }
    }

    private static void updateBounds(Connection connection, long id, Bounds bounds, long now)
            throws SQLException {
        String sql = """
                UPDATE wilderness_claims
                SET min_x = ?, max_x = ?, min_z = ?, max_z = ?, updated_at = ?
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, bounds.minX());
            statement.setInt(2, bounds.maxX());
            statement.setInt(3, bounds.minZ());
            statement.setInt(4, bounds.maxZ());
            statement.setLong(5, now);
            statement.setLong(6, id);
            statement.executeUpdate();
        }
    }

    private static boolean overlaps(Connection connection, String world, Bounds bounds, Long excludedId)
            throws SQLException {
        String sql = """
                SELECT 1 FROM wilderness_claims
                WHERE world_name = ?
                  AND NOT (max_x < ? OR min_x > ? OR max_z < ? OR min_z > ?)
                  AND (? IS NULL OR id <> ?)
                LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, world);
            statement.setInt(2, bounds.minX());
            statement.setInt(3, bounds.maxX());
            statement.setInt(4, bounds.minZ());
            statement.setInt(5, bounds.maxZ());
            if (excludedId == null) {
                statement.setNull(6, java.sql.Types.BIGINT);
                statement.setNull(7, java.sql.Types.BIGINT);
            } else {
                statement.setLong(6, excludedId);
                statement.setLong(7, excludedId);
            }
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static int usedBlocks(Connection connection, UUID owner, Long excludedId) throws SQLException {
        String sql = """
                SELECT COALESCE(SUM((max_x - min_x + 1) * (max_z - min_z + 1)), 0)
                FROM wilderness_claims
                WHERE owner_uuid = ? AND (? IS NULL OR id <> ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner.toString());
            if (excludedId == null) {
                statement.setNull(2, java.sql.Types.BIGINT);
                statement.setNull(3, java.sql.Types.BIGINT);
            } else {
                statement.setLong(2, excludedId);
                statement.setLong(3, excludedId);
            }
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return Math.toIntExact(results.getLong(1));
            }
        }
    }

    private int entitlement(Connection connection, UUID player) throws SQLException {
        return Math.addExact(freeBlocks, purchasedBlocks(connection, player));
    }

    private static int purchasedBlocks(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT purchased_blocks FROM claim_accounts WHERE player_uuid = ?")) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        }
    }

    private static void setPurchasedBlocks(Connection connection, UUID player, int blocks, long now)
            throws SQLException {
        String sql = """
                INSERT INTO claim_accounts(player_uuid, purchased_blocks, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE
                    SET purchased_blocks = excluded.purchased_blocks, updated_at = excluded.updated_at
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            statement.setInt(2, blocks);
            statement.setLong(3, now);
            statement.executeUpdate();
        }
    }

    private static ClaimResult verifyOwnerAndTarget(
            Connection connection, UUID actor, long claimId, UUID target) throws SQLException {
        Optional<LandClaim> selected = find(connection, claimId);
        if (selected.isEmpty()) {
            return ClaimResult.CLAIM_NOT_FOUND;
        }
        if (!selected.get().ownerId().equals(actor)) {
            return ClaimResult.NO_PERMISSION;
        }
        return accountExists(connection, target) ? ClaimResult.SUCCESS : ClaimResult.CITIZEN_NOT_FOUND;
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

    private static long playerBalance(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_cents FROM accounts WHERE player_uuid = ?")) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("Player account not found");
                }
                return results.getLong(1);
            }
        }
    }

    private static void insertPlayerLedger(Connection connection, UUID player, long amount, long now)
            throws SQLException {
        String sql = """
                INSERT INTO ledger_entries(player_uuid, counterparty_uuid, amount_cents, entry_type, created_at)
                VALUES (?, NULL, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            statement.setLong(2, amount);
            statement.setString(3, LedgerEntryType.CLAIM_BLOCK_PURCHASE.name());
            statement.setLong(4, now);
            statement.executeUpdate();
        }
    }

    private static Bounds bounds(int firstX, int firstZ, int secondX, int secondZ) {
        int minX = Math.min(firstX, secondX);
        int maxX = Math.max(firstX, secondX);
        int minZ = Math.min(firstZ, secondZ);
        int maxZ = Math.max(firstZ, secondZ);
        int width = Math.addExact(Math.subtractExact(maxX, minX), 1);
        int depth = Math.addExact(Math.subtractExact(maxZ, minZ), 1);
        int area = Math.multiplyExact(width, depth);
        return new Bounds(minX, maxX, minZ, maxZ, area);
    }

    private record Bounds(int minX, int maxX, int minZ, int maxZ, int area) {
    }
}
