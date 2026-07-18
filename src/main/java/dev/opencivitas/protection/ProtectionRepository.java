package dev.opencivitas.protection;

import dev.opencivitas.database.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ProtectionRepository {
    private static final String SELECT_PROTECTION = """
            SELECT b.*, p.last_name AS owner_name
            FROM block_protections b JOIN players p ON p.uuid = b.owner_uuid
            """;

    private final Database database;
    private final int maximumProtections;
    private final int maximumGroups;
    private final int maximumGroupMembers;

    public ProtectionRepository(Database database, ProtectionPolicy policy) {
        this(database, policy.maximumProtections(), policy.maximumGroups(), policy.maximumGroupMembers());
    }

    ProtectionRepository(
            Database database,
            int maximumProtections,
            int maximumGroups,
            int maximumGroupMembers
    ) {
        if (maximumProtections < 1 || maximumGroups < 1 || maximumGroupMembers < 1) {
            throw new IllegalArgumentException("Protection limits must be positive");
        }
        this.database = database;
        this.maximumProtections = maximumProtections;
        this.maximumGroups = maximumGroups;
        this.maximumGroupMembers = maximumGroupMembers;
    }

    public ProtectionState loadState() throws SQLException {
        try (Connection connection = database.openConnection()) {
            Map<ProtectionKey, Map<ProtectionSource, ProtectionAccess>> access = loadAccess(connection);
            List<BlockProtection> protections = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    SELECT_PROTECTION + " ORDER BY b.world, b.x, b.y, b.z");
                 ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    ProtectionKey key = key(results);
                    protections.add(readProtection(results, access.getOrDefault(key, Map.of())));
                }
            }
            return new ProtectionState(protections, loadGroups(connection), loadTrust(connection));
        }
    }

    public ProtectionOperation<BlockProtection> create(
            UUID ownerId,
            ProtectionKey key,
            ProtectionType type,
            long now
    ) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!citizenExists(connection, ownerId)) {
                    connection.rollback();
                    return ProtectionOperation.failed(ProtectionResult.CITIZEN_NOT_FOUND);
                }
                if (find(connection, key).isPresent()) {
                    connection.rollback();
                    return ProtectionOperation.failed(ProtectionResult.ALREADY_PROTECTED);
                }
                if (count(connection, "block_protections", "owner_uuid", ownerId.toString())
                        >= maximumProtections) {
                    connection.rollback();
                    return ProtectionOperation.failed(ProtectionResult.LIMIT_REACHED);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO block_protections(
                            world, x, y, z, owner_uuid, protection_type, auto_close, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, 0, ?)
                        """)) {
                    bind(statement, key, 1);
                    statement.setString(5, ownerId.toString());
                    statement.setString(6, type.name());
                    statement.setLong(7, now);
                    statement.executeUpdate();
                }
                BlockProtection protection = find(connection, key).orElseThrow();
                connection.commit();
                return ProtectionOperation.success(protection);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public ProtectionOperation<BlockProtection> delete(ProtectionKey key) throws SQLException {
        return delete(key, null);
    }

    public ProtectionOperation<BlockProtection> delete(ProtectionKey key, UUID expectedOwnerId)
            throws SQLException {
        try (Connection connection = database.openConnection()) {
            Optional<BlockProtection> protection = find(connection, key);
            if (protection.isEmpty()) {
                return ProtectionOperation.failed(ProtectionResult.PROTECTION_NOT_FOUND);
            }
            if (expectedOwnerId != null && !protection.orElseThrow().ownerId().equals(expectedOwnerId)) {
                return ProtectionOperation.failed(ProtectionResult.NOT_OWNER);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM block_protections WHERE world = ? AND x = ? AND y = ? AND z = ?
                    """)) {
                bind(statement, key, 1);
                statement.executeUpdate();
            }
            return ProtectionOperation.success(protection.orElseThrow());
        }
    }

    public ProtectionOperation<BlockProtection> transfer(ProtectionKey key, UUID ownerId) throws SQLException {
        return transfer(key, null, ownerId);
    }

    public ProtectionOperation<BlockProtection> transfer(
            ProtectionKey key,
            UUID expectedOwnerId,
            UUID ownerId
    ) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!citizenExists(connection, ownerId)) {
                    connection.rollback();
                    return ProtectionOperation.failed(ProtectionResult.CITIZEN_NOT_FOUND);
                }
                Optional<BlockProtection> current = find(connection, key);
                if (current.isEmpty()) {
                    connection.rollback();
                    return ProtectionOperation.failed(ProtectionResult.PROTECTION_NOT_FOUND);
                }
                if (expectedOwnerId != null && !current.orElseThrow().ownerId().equals(expectedOwnerId)) {
                    connection.rollback();
                    return ProtectionOperation.failed(ProtectionResult.NOT_OWNER);
                }
                if (count(connection, "block_protections", "owner_uuid", ownerId.toString())
                        >= maximumProtections) {
                    connection.rollback();
                    return ProtectionOperation.failed(ProtectionResult.LIMIT_REACHED);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE block_protections SET owner_uuid = ?
                        WHERE world = ? AND x = ? AND y = ? AND z = ?
                        """)) {
                    statement.setString(1, ownerId.toString());
                    bind(statement, key, 2);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM block_protection_access
                        WHERE world = ? AND x = ? AND y = ? AND z = ?
                        """)) {
                    bind(statement, key, 1);
                    statement.executeUpdate();
                }
                BlockProtection updated = find(connection, key).orElseThrow();
                connection.commit();
                return ProtectionOperation.success(updated);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public ProtectionOperation<BlockProtection> setAutoClose(ProtectionKey key, boolean enabled)
            throws SQLException {
        return setAutoClose(key, null, enabled);
    }

    public ProtectionOperation<BlockProtection> setAutoClose(
            ProtectionKey key,
            UUID expectedOwnerId,
            boolean enabled
    ) throws SQLException {
        try (Connection connection = database.openConnection()) {
            Optional<BlockProtection> current = find(connection, key);
            if (current.isEmpty()) {
                return ProtectionOperation.failed(ProtectionResult.PROTECTION_NOT_FOUND);
            }
            if (expectedOwnerId != null && !current.orElseThrow().ownerId().equals(expectedOwnerId)) {
                return ProtectionOperation.failed(ProtectionResult.NOT_OWNER);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE block_protections SET auto_close = ?
                    WHERE world = ? AND x = ? AND y = ? AND z = ?
                    """)) {
                statement.setInt(1, enabled ? 1 : 0);
                bind(statement, key, 2);
                statement.executeUpdate();
            }
            return ProtectionOperation.success(find(connection, key).orElseThrow());
        }
    }

    public ProtectionOperation<BlockProtection> modifyAccess(
            ProtectionKey key,
            ProtectionSource source,
            ProtectionAccess access,
            boolean adding,
            long now
    ) throws SQLException {
        return modifyAccess(key, null, source, access, adding, now);
    }

    public ProtectionOperation<BlockProtection> modifyAccess(
            ProtectionKey key,
            UUID expectedOwnerId,
            ProtectionSource source,
            ProtectionAccess access,
            boolean adding,
            long now
    ) throws SQLException {
        try (Connection connection = database.openConnection()) {
            Optional<BlockProtection> found = find(connection, key);
            if (found.isEmpty()) return ProtectionOperation.failed(ProtectionResult.PROTECTION_NOT_FOUND);
            if (expectedOwnerId != null && !found.orElseThrow().ownerId().equals(expectedOwnerId)) {
                return ProtectionOperation.failed(ProtectionResult.NOT_OWNER);
            }
            if (adding && source.type() == ProtectionSourceType.GROUP
                    && findGroup(connection, found.orElseThrow().ownerId(), source.identifier()).isEmpty()) {
                return ProtectionOperation.failed(ProtectionResult.GROUP_NOT_FOUND);
            }
            if (adding && source.type() == ProtectionSourceType.PLAYER
                    && !citizenExists(connection, UUID.fromString(source.identifier()))) {
                return ProtectionOperation.failed(ProtectionResult.CITIZEN_NOT_FOUND);
            }

            int changed;
            if (adding) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO block_protection_access(
                            world, x, y, z, source_type, source_identifier, access_level, added_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(world, x, y, z, source_type, source_identifier)
                        DO UPDATE SET access_level = excluded.access_level, added_at = excluded.added_at
                        """)) {
                    bind(statement, key, 1);
                    statement.setString(5, source.type().name());
                    statement.setString(6, source.identifier());
                    statement.setString(7, access.name());
                    statement.setLong(8, now);
                    changed = statement.executeUpdate();
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM block_protection_access
                        WHERE world = ? AND x = ? AND y = ? AND z = ?
                          AND source_type = ? AND source_identifier = ?
                        """)) {
                    bind(statement, key, 1);
                    statement.setString(5, source.type().name());
                    statement.setString(6, source.identifier());
                    changed = statement.executeUpdate();
                }
            }
            if (changed == 0) return ProtectionOperation.failed(ProtectionResult.SOURCE_NOT_FOUND);
            return ProtectionOperation.success(find(connection, key).orElseThrow());
        }
    }

    public ProtectionOperation<ProtectionGroup> createGroup(
            UUID ownerId,
            String name,
            long now
    ) throws SQLException {
        try (Connection connection = database.openConnection()) {
            if (!citizenExists(connection, ownerId)) {
                return ProtectionOperation.failed(ProtectionResult.CITIZEN_NOT_FOUND);
            }
            if (findGroup(connection, ownerId, name).isPresent()) {
                return ProtectionOperation.failed(ProtectionResult.GROUP_EXISTS);
            }
            if (count(connection, "protection_groups", "owner_uuid", ownerId.toString()) >= maximumGroups) {
                return ProtectionOperation.failed(ProtectionResult.LIMIT_REACHED);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO protection_groups(owner_uuid, name, created_at) VALUES (?, ?, ?)
                    """)) {
                statement.setString(1, ownerId.toString());
                statement.setString(2, name);
                statement.setLong(3, now);
                statement.executeUpdate();
            }
            return ProtectionOperation.success(new ProtectionGroup(ownerId, name, Set.of()));
        }
    }

    public ProtectionOperation<ProtectionGroup> deleteGroup(UUID ownerId, String name) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<ProtectionGroup> group = findGroup(connection, ownerId, name);
                if (group.isEmpty()) {
                    connection.rollback();
                    return ProtectionOperation.failed(ProtectionResult.GROUP_NOT_FOUND);
                }
                try (PreparedStatement access = connection.prepareStatement("""
                        DELETE FROM block_protection_access
                        WHERE source_type = 'GROUP' AND source_identifier = ?
                          AND EXISTS (
                            SELECT 1 FROM block_protections b
                            WHERE b.world = block_protection_access.world
                              AND b.x = block_protection_access.x AND b.y = block_protection_access.y
                              AND b.z = block_protection_access.z AND b.owner_uuid = ?)
                        """)) {
                    access.setString(1, name);
                    access.setString(2, ownerId.toString());
                    access.executeUpdate();
                }
                try (PreparedStatement trust = connection.prepareStatement("""
                        DELETE FROM protection_trust
                        WHERE owner_uuid = ? AND source_type = 'GROUP' AND source_identifier = ?
                        """)) {
                    trust.setString(1, ownerId.toString());
                    trust.setString(2, name);
                    trust.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM protection_groups WHERE owner_uuid = ? AND name = ? COLLATE NOCASE
                        """)) {
                    statement.setString(1, ownerId.toString());
                    statement.setString(2, name);
                    statement.executeUpdate();
                }
                connection.commit();
                return ProtectionOperation.success(group.orElseThrow());
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public ProtectionOperation<ProtectionGroup> modifyGroup(
            UUID ownerId,
            String name,
            UUID memberId,
            boolean adding,
            long now
    ) throws SQLException {
        try (Connection connection = database.openConnection()) {
            Optional<ProtectionGroup> group = findGroup(connection, ownerId, name);
            if (group.isEmpty()) return ProtectionOperation.failed(ProtectionResult.GROUP_NOT_FOUND);
            if (!citizenExists(connection, memberId)) {
                return ProtectionOperation.failed(ProtectionResult.CITIZEN_NOT_FOUND);
            }
            if (adding && group.orElseThrow().members().size() >= maximumGroupMembers) {
                return ProtectionOperation.failed(ProtectionResult.LIMIT_REACHED);
            }
            int changed;
            if (adding) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT OR IGNORE INTO protection_group_members(
                            owner_uuid, group_name, player_uuid, added_at) VALUES (?, ?, ?, ?)
                        """)) {
                    statement.setString(1, ownerId.toString());
                    statement.setString(2, name);
                    statement.setString(3, memberId.toString());
                    statement.setLong(4, now);
                    changed = statement.executeUpdate();
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM protection_group_members
                        WHERE owner_uuid = ? AND group_name = ? COLLATE NOCASE AND player_uuid = ?
                        """)) {
                    statement.setString(1, ownerId.toString());
                    statement.setString(2, name);
                    statement.setString(3, memberId.toString());
                    changed = statement.executeUpdate();
                }
            }
            if (changed == 0) return ProtectionOperation.failed(
                    adding ? ProtectionResult.MEMBER_EXISTS : ProtectionResult.MEMBER_NOT_FOUND);
            return ProtectionOperation.success(findGroup(connection, ownerId, name).orElseThrow());
        }
    }

    public ProtectionOperation<Map<ProtectionSource, ProtectionAccess>> modifyTrust(
            UUID ownerId,
            ProtectionSource source,
            ProtectionAccess access,
            boolean adding,
            long now
    ) throws SQLException {
        try (Connection connection = database.openConnection()) {
            if (!citizenExists(connection, ownerId)) {
                return ProtectionOperation.failed(ProtectionResult.CITIZEN_NOT_FOUND);
            }
            if (adding && source.type() == ProtectionSourceType.GROUP
                    && findGroup(connection, ownerId, source.identifier()).isEmpty()) {
                return ProtectionOperation.failed(ProtectionResult.GROUP_NOT_FOUND);
            }
            if (adding && source.type() == ProtectionSourceType.PLAYER
                    && !citizenExists(connection, UUID.fromString(source.identifier()))) {
                return ProtectionOperation.failed(ProtectionResult.CITIZEN_NOT_FOUND);
            }
            int changed;
            if (adding) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO protection_trust(
                            owner_uuid, source_type, source_identifier, access_level, added_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT(owner_uuid, source_type, source_identifier)
                        DO UPDATE SET access_level = excluded.access_level, added_at = excluded.added_at
                        """)) {
                    statement.setString(1, ownerId.toString());
                    statement.setString(2, source.type().name());
                    statement.setString(3, source.identifier());
                    statement.setString(4, access.name());
                    statement.setLong(5, now);
                    changed = statement.executeUpdate();
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM protection_trust
                        WHERE owner_uuid = ? AND source_type = ? AND source_identifier = ?
                        """)) {
                    statement.setString(1, ownerId.toString());
                    statement.setString(2, source.type().name());
                    statement.setString(3, source.identifier());
                    changed = statement.executeUpdate();
                }
            }
            if (changed == 0) return ProtectionOperation.failed(ProtectionResult.SOURCE_NOT_FOUND);
            return ProtectionOperation.success(loadTrust(connection).getOrDefault(ownerId, Map.of()));
        }
    }

    private Optional<BlockProtection> find(Connection connection, ProtectionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_PROTECTION + """
                WHERE b.world = ? AND b.x = ? AND b.y = ? AND b.z = ?
                """)) {
            bind(statement, key, 1);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) return Optional.empty();
                return Optional.of(readProtection(results, loadAccess(connection, key)));
            }
        }
    }

    private static BlockProtection readProtection(
            ResultSet results,
            Map<ProtectionSource, ProtectionAccess> access
    ) throws SQLException {
        return new BlockProtection(
                key(results),
                UUID.fromString(results.getString("owner_uuid")),
                results.getString("owner_name"),
                ProtectionType.valueOf(results.getString("protection_type")),
                results.getInt("auto_close") == 1,
                Instant.ofEpochMilli(results.getLong("created_at")),
                access);
    }

    private static Map<ProtectionKey, Map<ProtectionSource, ProtectionAccess>> loadAccess(Connection connection)
            throws SQLException {
        Map<ProtectionKey, Map<ProtectionSource, ProtectionAccess>> access = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM block_protection_access
                ORDER BY world, x, y, z, source_type, source_identifier
                """); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                access.computeIfAbsent(key(results), ignored -> new HashMap<>()).put(
                        new ProtectionSource(
                                ProtectionSourceType.valueOf(results.getString("source_type")),
                                results.getString("source_identifier")),
                        ProtectionAccess.valueOf(results.getString("access_level")));
            }
        }
        return access;
    }

    private static Map<ProtectionSource, ProtectionAccess> loadAccess(
            Connection connection,
            ProtectionKey key
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT source_type, source_identifier, access_level
                FROM block_protection_access
                WHERE world = ? AND x = ? AND y = ? AND z = ?
                """)) {
            bind(statement, key, 1);
            Map<ProtectionSource, ProtectionAccess> access = new HashMap<>();
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    access.put(new ProtectionSource(
                                    ProtectionSourceType.valueOf(results.getString("source_type")),
                                    results.getString("source_identifier")),
                            ProtectionAccess.valueOf(results.getString("access_level")));
                }
            }
            return Map.copyOf(access);
        }
    }

    private static List<ProtectionGroup> loadGroups(Connection connection) throws SQLException {
        Map<GroupKey, Set<UUID>> members = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner_uuid, group_name, player_uuid FROM protection_group_members
                ORDER BY owner_uuid, group_name, player_uuid
                """); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                GroupKey key = new GroupKey(
                        UUID.fromString(results.getString("owner_uuid")), results.getString("group_name"));
                members.computeIfAbsent(key, ignored -> new HashSet<>())
                        .add(UUID.fromString(results.getString("player_uuid")));
            }
        }
        List<ProtectionGroup> groups = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner_uuid, name FROM protection_groups ORDER BY owner_uuid, name COLLATE NOCASE
                """); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                GroupKey key = new GroupKey(
                        UUID.fromString(results.getString("owner_uuid")), results.getString("name"));
                groups.add(new ProtectionGroup(key.ownerId(), key.name(), members.getOrDefault(key, Set.of())));
            }
        }
        return List.copyOf(groups);
    }

    private static Optional<ProtectionGroup> findGroup(
            Connection connection,
            UUID ownerId,
            String name
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT name FROM protection_groups WHERE owner_uuid = ? AND name = ? COLLATE NOCASE
                """)) {
            statement.setString(1, ownerId.toString());
            statement.setString(2, name);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) return Optional.empty();
                String storedName = results.getString("name");
                Set<UUID> members = new HashSet<>();
                try (PreparedStatement memberStatement = connection.prepareStatement("""
                        SELECT player_uuid FROM protection_group_members
                        WHERE owner_uuid = ? AND group_name = ? COLLATE NOCASE ORDER BY player_uuid
                        """)) {
                    memberStatement.setString(1, ownerId.toString());
                    memberStatement.setString(2, storedName);
                    try (ResultSet memberResults = memberStatement.executeQuery()) {
                        while (memberResults.next()) {
                            members.add(UUID.fromString(memberResults.getString("player_uuid")));
                        }
                    }
                }
                return Optional.of(new ProtectionGroup(ownerId, storedName, members));
            }
        }
    }

    private static Map<UUID, Map<ProtectionSource, ProtectionAccess>> loadTrust(Connection connection)
            throws SQLException {
        Map<UUID, Map<ProtectionSource, ProtectionAccess>> trust = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM protection_trust ORDER BY owner_uuid, source_type, source_identifier
                """); ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                UUID ownerId = UUID.fromString(results.getString("owner_uuid"));
                trust.computeIfAbsent(ownerId, ignored -> new HashMap<>()).put(
                        new ProtectionSource(
                                ProtectionSourceType.valueOf(results.getString("source_type")),
                                results.getString("source_identifier")),
                        ProtectionAccess.valueOf(results.getString("access_level")));
            }
        }
        return trust;
    }

    private static boolean citizenExists(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM players WHERE uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static int count(Connection connection, String table, String column, String value)
            throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getInt(1);
            }
        }
    }

    private static void bind(PreparedStatement statement, ProtectionKey key, int start) throws SQLException {
        statement.setString(start, key.world());
        statement.setInt(start + 1, key.x());
        statement.setInt(start + 2, key.y());
        statement.setInt(start + 3, key.z());
    }

    private static ProtectionKey key(ResultSet results) throws SQLException {
        return new ProtectionKey(
                results.getString("world"),
                results.getInt("x"), results.getInt("y"), results.getInt("z"));
    }

    private record GroupKey(UUID ownerId, String name) {
    }
}
