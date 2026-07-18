package dev.opencivitas.navigation;

import dev.opencivitas.database.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class NavigationRepository {
    private final Database database;

    public NavigationRepository(Database database) {
        this.database = database;
    }

    public NavigationOperation<SavedLocation> setHome(
            UUID playerId, SavedLocation home, int maximumHomes, long now) throws SQLException {
        if (!valid(home)) return NavigationOperation.result(NavigationResult.INVALID_NAME);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!citizenExists(connection, playerId)) {
                    connection.rollback();
                    return NavigationOperation.result(NavigationResult.CITIZEN_NOT_FOUND);
                }
                if (!homeExists(connection, playerId, home.id()) && homeCount(connection, playerId) >= maximumHomes) {
                    connection.rollback();
                    return NavigationOperation.result(NavigationResult.HOME_LIMIT_REACHED);
                }
                String sql = """
                        INSERT INTO player_homes(
                            player_uuid, home_name, world_name, x, y, z, yaw, pitch, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(player_uuid, home_name) DO UPDATE SET
                            world_name = excluded.world_name, x = excluded.x, y = excluded.y,
                            z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch,
                            updated_at = excluded.updated_at
                        """;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, playerId.toString());
                    bindLocation(statement, home, 2);
                    statement.setLong(9, now);
                    statement.executeUpdate();
                }
                connection.commit();
                return NavigationOperation.success(home);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public Optional<SavedLocation> home(UUID playerId, String name) throws SQLException {
        if (!NavigationPolicy.validId(name)) return Optional.empty();
        String sql = """
                SELECT home_name, world_name, x, y, z, yaw, pitch FROM player_homes
                WHERE player_uuid = ? AND home_name = ? COLLATE NOCASE
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, name);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(read(results, "home_name")) : Optional.empty();
            }
        }
    }

    public List<SavedLocation> homes(UUID playerId) throws SQLException {
        String sql = """
                SELECT home_name, world_name, x, y, z, yaw, pitch FROM player_homes
                WHERE player_uuid = ? ORDER BY home_name COLLATE NOCASE
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<SavedLocation> homes = new ArrayList<>();
                while (results.next()) homes.add(read(results, "home_name"));
                return List.copyOf(homes);
            }
        }
    }

    public NavigationResult deleteHome(UUID playerId, String name) throws SQLException {
        if (!NavigationPolicy.validId(name)) return NavigationResult.INVALID_NAME;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM player_homes WHERE player_uuid = ? AND home_name = ? COLLATE NOCASE")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, name);
            return statement.executeUpdate() == 1 ? NavigationResult.SUCCESS : NavigationResult.HOME_NOT_FOUND;
        }
    }

    public NavigationOperation<SavedLocation> setWarp(
            SavedLocation warp, UUID actor, long now) throws SQLException {
        if (!valid(warp)) return NavigationOperation.result(NavigationResult.INVALID_NAME);
        String sql = """
                INSERT INTO civic_warps(warp_id, world_name, x, y, z, yaw, pitch, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(warp_id) DO UPDATE SET
                    world_name = excluded.world_name, x = excluded.x, y = excluded.y,
                    z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch,
                    updated_by = excluded.updated_by, updated_at = excluded.updated_at
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindLocation(statement, warp, 1);
            if (actor == null) statement.setNull(8, java.sql.Types.VARCHAR);
            else statement.setString(8, actor.toString());
            statement.setLong(9, now);
            statement.executeUpdate();
            return NavigationOperation.success(warp);
        }
    }

    public Optional<SavedLocation> warp(String id) throws SQLException {
        if (!NavigationPolicy.validId(id)) return Optional.empty();
        String sql = "SELECT warp_id, world_name, x, y, z, yaw, pitch FROM civic_warps "
                + "WHERE warp_id = ? COLLATE NOCASE";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(read(results, "warp_id")) : Optional.empty();
            }
        }
    }

    public List<SavedLocation> warps() throws SQLException {
        String sql = "SELECT warp_id, world_name, x, y, z, yaw, pitch FROM civic_warps "
                + "ORDER BY warp_id COLLATE NOCASE";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            List<SavedLocation> warps = new ArrayList<>();
            while (results.next()) warps.add(read(results, "warp_id"));
            return List.copyOf(warps);
        }
    }

    public NavigationResult deleteWarp(String id) throws SQLException {
        if (!NavigationPolicy.validId(id)) return NavigationResult.INVALID_NAME;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM civic_warps WHERE warp_id = ? COLLATE NOCASE")) {
            statement.setString(1, id);
            return statement.executeUpdate() == 1 ? NavigationResult.SUCCESS : NavigationResult.WARP_NOT_FOUND;
        }
    }

    private static void bindLocation(PreparedStatement statement, SavedLocation location, int start)
            throws SQLException {
        statement.setString(start, location.id().toLowerCase(java.util.Locale.ROOT));
        statement.setString(start + 1, location.world());
        statement.setDouble(start + 2, location.x());
        statement.setDouble(start + 3, location.y());
        statement.setDouble(start + 4, location.z());
        statement.setFloat(start + 5, location.yaw());
        statement.setFloat(start + 6, location.pitch());
    }

    private static SavedLocation read(ResultSet results, String idColumn) throws SQLException {
        return new SavedLocation(results.getString(idColumn), results.getString("world_name"),
                results.getDouble("x"), results.getDouble("y"), results.getDouble("z"),
                results.getFloat("yaw"), results.getFloat("pitch"));
    }

    private static boolean valid(SavedLocation location) {
        return NavigationPolicy.validId(location.id()) && location.world() != null && !location.world().isBlank()
                && Double.isFinite(location.x()) && Double.isFinite(location.y()) && Double.isFinite(location.z())
                && Float.isFinite(location.yaw()) && Float.isFinite(location.pitch());
    }

    private static boolean citizenExists(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static boolean homeExists(Connection connection, UUID playerId, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM player_homes WHERE player_uuid = ? AND home_name = ? COLLATE NOCASE
                """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, name);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static int homeCount(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM player_homes WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        }
    }
}
