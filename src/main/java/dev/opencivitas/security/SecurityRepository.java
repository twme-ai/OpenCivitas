package dev.opencivitas.security;

import dev.opencivitas.database.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class SecurityRepository {
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final String CAMERA_SELECT = """
            SELECT id, owner_uuid, name, world, x, y, z, yaw, pitch, created_at
            FROM security_cameras
            """;
    private static final String COMPUTER_SELECT = """
            SELECT id, owner_uuid, name, world, x, y, z, group_id, public_access, created_at
            FROM security_computers
            """;

    private final Database database;
    private final int maximumCameras;
    private final int maximumComputers;
    private final int maximumGroups;

    public SecurityRepository(Database database, SecurityPolicy policy) {
        this(database, policy.maximumCameras(), policy.maximumComputers(), policy.maximumGroups());
    }

    SecurityRepository(Database database, int maximumCameras, int maximumComputers, int maximumGroups) {
        this.database = database;
        this.maximumCameras = maximumCameras;
        this.maximumComputers = maximumComputers;
        this.maximumGroups = maximumGroups;
    }

    public SecurityOperation<SecurityCamera> placeCamera(
            UUID owner, String world, double x, double y, double z,
            float yaw, float pitch, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!citizenExists(connection, owner)) return rollbackOperation(connection, SecurityResult.CITIZEN_NOT_FOUND);
                if (count(connection, "security_cameras", owner) >= maximumCameras) {
                    return rollbackOperation(connection, SecurityResult.LIMIT_REACHED);
                }
                if (cameraAt(connection, world, x, y, z)) {
                    return rollbackOperation(connection, SecurityResult.LOCATION_OCCUPIED);
                }
                long id;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO security_cameras(
                            owner_uuid, name, world, x, y, z, yaw, pitch, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, owner.toString());
                    statement.setString(2, "pending-" + UUID.randomUUID());
                    statement.setString(3, world);
                    statement.setDouble(4, x);
                    statement.setDouble(5, y);
                    statement.setDouble(6, z);
                    statement.setFloat(7, yaw);
                    statement.setFloat(8, pitch);
                    statement.setLong(9, now);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Camera id was not returned");
                        id = keys.getLong(1);
                    }
                }
                String name = "camera-" + id;
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE security_cameras SET name = ? WHERE id = ?")) {
                    statement.setString(1, name);
                    statement.setLong(2, id);
                    statement.executeUpdate();
                }
                SecurityCamera camera = new SecurityCamera(
                        id, owner, name, world, x, y, z, yaw, pitch, now);
                connection.commit();
                return SecurityOperation.success(camera);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public SecurityOperation<SecurityComputer> placeComputer(
            UUID owner, String world, int x, int y, int z, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!citizenExists(connection, owner)) return rollbackOperation(connection, SecurityResult.CITIZEN_NOT_FOUND);
                if (count(connection, "security_computers", owner) >= maximumComputers) {
                    return rollbackOperation(connection, SecurityResult.LIMIT_REACHED);
                }
                if (computerAt(connection, world, x, y, z).isPresent()) {
                    return rollbackOperation(connection, SecurityResult.LOCATION_OCCUPIED);
                }
                long id;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO security_computers(
                            owner_uuid, name, world, x, y, z, group_id, public_access, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, NULL, 0, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, owner.toString());
                    statement.setString(2, "pending-" + UUID.randomUUID());
                    statement.setString(3, world);
                    statement.setInt(4, x);
                    statement.setInt(5, y);
                    statement.setInt(6, z);
                    statement.setLong(7, now);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Computer id was not returned");
                        id = keys.getLong(1);
                    }
                }
                String name = "computer-" + id;
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE security_computers SET name = ? WHERE id = ?")) {
                    statement.setString(1, name);
                    statement.setLong(2, id);
                    statement.executeUpdate();
                }
                SecurityComputer computer = new SecurityComputer(
                        id, owner, name, world, x, y, z, null, false, now);
                connection.commit();
                return SecurityOperation.success(computer);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public SecurityOperation<CameraGroup> createGroup(UUID owner, String rawName, long now) throws SQLException {
        String name = normalize(rawName);
        if (name == null) return SecurityOperation.result(SecurityResult.NAME_TAKEN);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!citizenExists(connection, owner)) return rollbackOperation(connection, SecurityResult.CITIZEN_NOT_FOUND);
                if (count(connection, "camera_groups", owner) >= maximumGroups) {
                    return rollbackOperation(connection, SecurityResult.LIMIT_REACHED);
                }
                if (group(connection, owner, name).isPresent()) {
                    return rollbackOperation(connection, SecurityResult.NAME_TAKEN);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO camera_groups(owner_uuid, name, created_at) VALUES (?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, owner.toString());
                    statement.setString(2, name);
                    statement.setLong(3, now);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Camera group id was not returned");
                        CameraGroup group = new CameraGroup(keys.getLong(1), owner, name, now);
                        connection.commit();
                        return SecurityOperation.success(group);
                    }
                }
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public SecurityResult deleteGroup(UUID owner, String rawName) throws SQLException {
        String name = normalize(rawName);
        if (name == null) return SecurityResult.GROUP_NOT_FOUND;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM camera_groups WHERE owner_uuid = ? AND name = ? COLLATE NOCASE")) {
            statement.setString(1, owner.toString());
            statement.setString(2, name);
            return statement.executeUpdate() == 1 ? SecurityResult.SUCCESS : SecurityResult.GROUP_NOT_FOUND;
        }
    }

    public SecurityResult addCamera(UUID owner, String groupName, String cameraName, long now)
            throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                CameraGroup group = group(connection, owner, normalize(groupName)).orElse(null);
                if (group == null) return rollbackResult(connection, SecurityResult.GROUP_NOT_FOUND);
                SecurityCamera camera = camera(connection, owner, normalize(cameraName)).orElse(null);
                if (camera == null) return rollbackResult(connection, SecurityResult.CAMERA_NOT_FOUND);
                try (PreparedStatement existing = connection.prepareStatement("""
                        SELECT 1 FROM camera_group_members WHERE group_id = ? AND camera_id = ?
                        """)) {
                    existing.setLong(1, group.id());
                    existing.setLong(2, camera.id());
                    try (ResultSet results = existing.executeQuery()) {
                        if (results.next()) return rollbackResult(connection, SecurityResult.ALREADY_MEMBER);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO camera_group_members(group_id, camera_id, position, added_at)
                        VALUES (?, ?, (SELECT COALESCE(MAX(position) + 1, 0)
                                      FROM camera_group_members WHERE group_id = ?), ?)
                        """)) {
                    statement.setLong(1, group.id());
                    statement.setLong(2, camera.id());
                    statement.setLong(3, group.id());
                    statement.setLong(4, now);
                    statement.executeUpdate();
                }
                connection.commit();
                return SecurityResult.SUCCESS;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public SecurityResult removeCamera(UUID owner, String groupName, String cameraName) throws SQLException {
        try (Connection connection = database.openConnection()) {
            CameraGroup group = group(connection, owner, normalize(groupName)).orElse(null);
            if (group == null) return SecurityResult.GROUP_NOT_FOUND;
            SecurityCamera camera = camera(connection, owner, normalize(cameraName)).orElse(null);
            if (camera == null) return SecurityResult.CAMERA_NOT_FOUND;
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM camera_group_members WHERE group_id = ? AND camera_id = ?
                    """)) {
                statement.setLong(1, group.id());
                statement.setLong(2, camera.id());
                return statement.executeUpdate() == 1 ? SecurityResult.SUCCESS : SecurityResult.NOT_MEMBER;
            }
        }
    }

    public SecurityOperation<SecurityCamera> renameCamera(
            UUID owner, String oldName, String newName) throws SQLException {
        String normalized = normalize(newName);
        if (normalized == null) return SecurityOperation.result(SecurityResult.NAME_TAKEN);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                SecurityCamera camera = camera(connection, owner, normalize(oldName)).orElse(null);
                if (camera == null) return rollbackOperation(connection, SecurityResult.CAMERA_NOT_FOUND);
                if (camera(connection, owner, normalized).isPresent()) {
                    return rollbackOperation(connection, SecurityResult.NAME_TAKEN);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE security_cameras SET name = ? WHERE id = ?")) {
                    statement.setString(1, normalized);
                    statement.setLong(2, camera.id());
                    statement.executeUpdate();
                }
                SecurityCamera renamed = new SecurityCamera(camera.id(), camera.ownerId(), normalized,
                        camera.world(), camera.x(), camera.y(), camera.z(), camera.yaw(), camera.pitch(),
                        camera.createdAt());
                connection.commit();
                return SecurityOperation.success(renamed);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public SecurityOperation<SecurityCamera> rotateCamera(
            UUID owner, long cameraId, float yawDelta, float pitchDelta) throws SQLException {
        try (Connection connection = database.openConnection()) {
            SecurityCamera camera = camera(connection, cameraId).orElse(null);
            if (camera == null) return SecurityOperation.result(SecurityResult.CAMERA_NOT_FOUND);
            if (!camera.ownerId().equals(owner)) return SecurityOperation.result(SecurityResult.NOT_OWNER);
            float yaw = normalizeYaw(camera.yaw() + yawDelta);
            float pitch = Math.max(-45, Math.min(45, camera.pitch() + pitchDelta));
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE security_cameras SET yaw = ?, pitch = ? WHERE id = ?")) {
                statement.setFloat(1, yaw);
                statement.setFloat(2, pitch);
                statement.setLong(3, camera.id());
                statement.executeUpdate();
            }
            return SecurityOperation.success(new SecurityCamera(camera.id(), camera.ownerId(), camera.name(),
                    camera.world(), camera.x(), camera.y(), camera.z(), yaw, pitch, camera.createdAt()));
        }
    }

    public SecurityOperation<SecurityCamera> deleteCamera(UUID owner, long cameraId) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                SecurityCamera camera = camera(connection, cameraId).orElse(null);
                if (camera == null) return rollbackOperation(connection, SecurityResult.CAMERA_NOT_FOUND);
                if (!camera.ownerId().equals(owner)) return rollbackOperation(connection, SecurityResult.NOT_OWNER);
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM security_cameras WHERE id = ?")) {
                    statement.setLong(1, camera.id());
                    statement.executeUpdate();
                }
                connection.commit();
                return SecurityOperation.success(camera);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public SecurityOperation<SecurityComputer> deleteComputer(UUID owner, long computerId) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                SecurityComputer computer = computer(connection, computerId).orElse(null);
                if (computer == null) return rollbackOperation(connection, SecurityResult.COMPUTER_NOT_FOUND);
                if (!computer.ownerId().equals(owner)) return rollbackOperation(connection, SecurityResult.NOT_OWNER);
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM security_computers WHERE id = ?")) {
                    statement.setLong(1, computer.id());
                    statement.executeUpdate();
                }
                connection.commit();
                return SecurityOperation.success(computer);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public SecurityOperation<SecurityComputer> assignGroup(
            UUID owner, long computerId, String groupName) throws SQLException {
        try (Connection connection = database.openConnection()) {
            SecurityComputer computer = computer(connection, computerId).orElse(null);
            if (computer == null) return SecurityOperation.result(SecurityResult.COMPUTER_NOT_FOUND);
            if (!computer.ownerId().equals(owner)) return SecurityOperation.result(SecurityResult.NOT_OWNER);
            CameraGroup group = group(connection, owner, normalize(groupName)).orElse(null);
            if (group == null) return SecurityOperation.result(SecurityResult.GROUP_NOT_FOUND);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE security_computers SET group_id = ? WHERE id = ?")) {
                statement.setLong(1, group.id());
                statement.setLong(2, computer.id());
                statement.executeUpdate();
            }
            return SecurityOperation.success(copy(computer, group.id(), computer.publicAccess()));
        }
    }

    public SecurityOperation<SecurityComputer> togglePublic(UUID owner, long computerId) throws SQLException {
        try (Connection connection = database.openConnection()) {
            SecurityComputer computer = computer(connection, computerId).orElse(null);
            if (computer == null) return SecurityOperation.result(SecurityResult.COMPUTER_NOT_FOUND);
            if (!computer.ownerId().equals(owner)) return SecurityOperation.result(SecurityResult.NOT_OWNER);
            boolean value = !computer.publicAccess();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE security_computers SET public_access = ? WHERE id = ?")) {
                statement.setInt(1, value ? 1 : 0);
                statement.setLong(2, computer.id());
                statement.executeUpdate();
            }
            return SecurityOperation.success(copy(computer, computer.groupId(), value));
        }
    }

    public SecurityResult grantAccess(
            UUID owner, long computerId, UUID target, long now) throws SQLException {
        if (owner.equals(target)) return SecurityResult.CANNOT_TARGET_SELF;
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                SecurityComputer computer = computer(connection, computerId).orElse(null);
                if (computer == null) return rollbackResult(connection, SecurityResult.COMPUTER_NOT_FOUND);
                if (!computer.ownerId().equals(owner)) return rollbackResult(connection, SecurityResult.NOT_OWNER);
                if (!citizenExists(connection, target)) return rollbackResult(connection, SecurityResult.CITIZEN_NOT_FOUND);
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT OR IGNORE INTO computer_access(
                            computer_id, player_uuid, granted_by, granted_at) VALUES (?, ?, ?, ?)
                        """)) {
                    statement.setLong(1, computerId);
                    statement.setString(2, target.toString());
                    statement.setString(3, owner.toString());
                    statement.setLong(4, now);
                    if (statement.executeUpdate() != 1) return rollbackResult(connection, SecurityResult.ACCESS_EXISTS);
                }
                connection.commit();
                return SecurityResult.SUCCESS;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public SecurityResult revokeAccess(UUID owner, long computerId, UUID target) throws SQLException {
        try (Connection connection = database.openConnection()) {
            SecurityComputer computer = computer(connection, computerId).orElse(null);
            if (computer == null) return SecurityResult.COMPUTER_NOT_FOUND;
            if (!computer.ownerId().equals(owner)) return SecurityResult.NOT_OWNER;
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM computer_access WHERE computer_id = ? AND player_uuid = ?
                    """)) {
                statement.setLong(1, computerId);
                statement.setString(2, target.toString());
                return statement.executeUpdate() == 1 ? SecurityResult.SUCCESS : SecurityResult.ACCESS_NOT_FOUND;
            }
        }
    }

    public boolean canAccess(long computerId, UUID playerId) throws SQLException {
        try (Connection connection = database.openConnection()) {
            SecurityComputer computer = computer(connection, computerId).orElse(null);
            if (computer == null) return false;
            if (computer.publicAccess() || computer.ownerId().equals(playerId)) return true;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT 1 FROM computer_access WHERE computer_id = ? AND player_uuid = ?
                    """)) {
                statement.setLong(1, computerId);
                statement.setString(2, playerId.toString());
                try (ResultSet results = statement.executeQuery()) {
                    return results.next();
                }
            }
        }
    }

    public Optional<ComputerDashboard> dashboard(long computerId) throws SQLException {
        try (Connection connection = database.openConnection()) {
            SecurityComputer computer = computer(connection, computerId).orElse(null);
            if (computer == null) return Optional.empty();
            Optional<CameraGroup> group = computer.groupId() == null
                    ? Optional.empty() : group(connection, computer.groupId());
            List<SecurityCamera> cameras = computer.groupId() == null
                    ? List.of() : groupCameras(connection, computer.groupId());
            return Optional.of(new ComputerDashboard(computer, group, cameras, access(connection, computerId)));
        }
    }

    public List<SecurityCamera> cameras(UUID owner) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     CAMERA_SELECT + " WHERE owner_uuid = ? ORDER BY name COLLATE NOCASE")) {
            statement.setString(1, owner.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<SecurityCamera> cameras = new ArrayList<>();
                while (results.next()) cameras.add(camera(results));
                return List.copyOf(cameras);
            }
        }
    }

    public List<SecurityComputer> computers(UUID owner) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     COMPUTER_SELECT + " WHERE owner_uuid = ? ORDER BY name COLLATE NOCASE")) {
            statement.setString(1, owner.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<SecurityComputer> computers = new ArrayList<>();
                while (results.next()) computers.add(computer(results));
                return List.copyOf(computers);
            }
        }
    }

    public List<CameraGroup> groups(UUID owner) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, owner_uuid, name, created_at FROM camera_groups
                     WHERE owner_uuid = ? ORDER BY name COLLATE NOCASE
                     """)) {
            statement.setString(1, owner.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<CameraGroup> groups = new ArrayList<>();
                while (results.next()) groups.add(group(results));
                return List.copyOf(groups);
            }
        }
    }

    public List<SecurityCamera> allCameras() throws SQLException {
        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(CAMERA_SELECT + " ORDER BY id")) {
            List<SecurityCamera> cameras = new ArrayList<>();
            while (results.next()) cameras.add(camera(results));
            return List.copyOf(cameras);
        }
    }

    public List<SecurityComputer> allComputers() throws SQLException {
        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(COMPUTER_SELECT + " ORDER BY id")) {
            List<SecurityComputer> computers = new ArrayList<>();
            while (results.next()) computers.add(computer(results));
            return List.copyOf(computers);
        }
    }

    private static List<SecurityCamera> groupCameras(Connection connection, long groupId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CAMERA_SELECT + """
                JOIN camera_group_members member ON member.camera_id = security_cameras.id
                WHERE member.group_id = ? ORDER BY member.position, member.camera_id
                """)) {
            statement.setLong(1, groupId);
            try (ResultSet results = statement.executeQuery()) {
                List<SecurityCamera> cameras = new ArrayList<>();
                while (results.next()) cameras.add(camera(results));
                return List.copyOf(cameras);
            }
        }
    }

    private static List<ComputerAccess> access(Connection connection, long computerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT access.player_uuid, player.last_name, access.granted_at
                FROM computer_access access JOIN players player ON player.uuid = access.player_uuid
                WHERE access.computer_id = ? ORDER BY player.last_name COLLATE NOCASE
                """)) {
            statement.setLong(1, computerId);
            try (ResultSet results = statement.executeQuery()) {
                List<ComputerAccess> access = new ArrayList<>();
                while (results.next()) access.add(new ComputerAccess(
                        UUID.fromString(results.getString("player_uuid")),
                        results.getString("last_name"), results.getLong("granted_at")));
                return List.copyOf(access);
            }
        }
    }

    private static Optional<SecurityCamera> camera(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(CAMERA_SELECT + " WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(camera(results)) : Optional.empty();
            }
        }
    }

    private static Optional<SecurityCamera> camera(
            Connection connection, UUID owner, String name) throws SQLException {
        if (name == null) return Optional.empty();
        try (PreparedStatement statement = connection.prepareStatement(
                CAMERA_SELECT + " WHERE owner_uuid = ? AND name = ? COLLATE NOCASE")) {
            statement.setString(1, owner.toString());
            statement.setString(2, name);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(camera(results)) : Optional.empty();
            }
        }
    }

    private static boolean cameraAt(
            Connection connection, String world, double x, double y, double z) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM security_cameras WHERE world = ? AND x = ? AND y = ? AND z = ?
                """)) {
            statement.setString(1, world);
            statement.setDouble(2, x);
            statement.setDouble(3, y);
            statement.setDouble(4, z);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static Optional<SecurityComputer> computer(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(COMPUTER_SELECT + " WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(computer(results)) : Optional.empty();
            }
        }
    }

    private static Optional<SecurityComputer> computerAt(
            Connection connection, String world, int x, int y, int z) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                COMPUTER_SELECT + " WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
            statement.setString(1, world);
            statement.setInt(2, x);
            statement.setInt(3, y);
            statement.setInt(4, z);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(computer(results)) : Optional.empty();
            }
        }
    }

    private static Optional<CameraGroup> group(
            Connection connection, UUID owner, String name) throws SQLException {
        if (name == null) return Optional.empty();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, owner_uuid, name, created_at FROM camera_groups
                WHERE owner_uuid = ? AND name = ? COLLATE NOCASE
                """)) {
            statement.setString(1, owner.toString());
            statement.setString(2, name);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(group(results)) : Optional.empty();
            }
        }
    }

    private static Optional<CameraGroup> group(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, owner_uuid, name, created_at FROM camera_groups WHERE id = ?
                """)) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(group(results)) : Optional.empty();
            }
        }
    }

    private static SecurityCamera camera(ResultSet results) throws SQLException {
        return new SecurityCamera(results.getLong("id"), UUID.fromString(results.getString("owner_uuid")),
                results.getString("name"), results.getString("world"), results.getDouble("x"),
                results.getDouble("y"), results.getDouble("z"), results.getFloat("yaw"),
                results.getFloat("pitch"), results.getLong("created_at"));
    }

    private static SecurityComputer computer(ResultSet results) throws SQLException {
        long groupId = results.getLong("group_id");
        return new SecurityComputer(results.getLong("id"), UUID.fromString(results.getString("owner_uuid")),
                results.getString("name"), results.getString("world"), results.getInt("x"),
                results.getInt("y"), results.getInt("z"), results.wasNull() ? null : groupId,
                results.getInt("public_access") == 1, results.getLong("created_at"));
    }

    private static CameraGroup group(ResultSet results) throws SQLException {
        return new CameraGroup(results.getLong("id"), UUID.fromString(results.getString("owner_uuid")),
                results.getString("name"), results.getLong("created_at"));
    }

    private static SecurityComputer copy(SecurityComputer computer, Long groupId, boolean publicAccess) {
        return new SecurityComputer(computer.id(), computer.ownerId(), computer.name(), computer.world(),
                computer.x(), computer.y(), computer.z(), groupId, publicAccess, computer.createdAt());
    }

    private static boolean citizenExists(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static int count(Connection connection, String table, UUID owner) throws SQLException {
        if (!List.of("security_cameras", "security_computers", "camera_groups").contains(table)) {
            throw new IllegalArgumentException("Unsupported security table");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE owner_uuid = ?")) {
            statement.setString(1, owner.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        }
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.toLowerCase(Locale.ROOT);
        return NAME.matcher(normalized).matches() ? normalized : null;
    }

    private static float normalizeYaw(float yaw) {
        float normalized = yaw % 360;
        return normalized < -180 ? normalized + 360 : normalized > 180 ? normalized - 360 : normalized;
    }

    private static <T> SecurityOperation<T> rollbackOperation(
            Connection connection, SecurityResult result) throws SQLException {
        connection.rollback();
        return SecurityOperation.result(result);
    }

    private static SecurityResult rollbackResult(Connection connection, SecurityResult result) throws SQLException {
        connection.rollback();
        return result;
    }
}
