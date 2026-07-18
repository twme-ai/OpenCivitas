package dev.opencivitas.vehicle;

import dev.opencivitas.database.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class VehicleRepository {
    private final Database database;

    public VehicleRepository(Database database) {
        this.database = database;
    }

    public VehicleOperation<VehicleState> create(VehicleState vehicle, int maximumOwned) throws SQLException {
        return create(vehicle, maximumOwned, new byte[0]);
    }

    public VehicleOperation<VehicleState> create(
            VehicleState vehicle, int maximumOwned, byte[] storage) throws SQLException {
        if (!valid(vehicle)) return VehicleOperation.result(VehicleResult.INVALID_STATE);
        if (storage == null || storage.length > 1_048_576) {
            return VehicleOperation.result(VehicleResult.INVALID_STATE);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!citizenExists(connection, vehicle.ownerId())) {
                    connection.rollback();
                    return VehicleOperation.result(VehicleResult.CITIZEN_NOT_FOUND);
                }
                if (ownedCount(connection, vehicle.ownerId()) >= maximumOwned) {
                    connection.rollback();
                    return VehicleOperation.result(VehicleResult.OWNER_LIMIT_REACHED);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO vehicles(
                            vehicle_uuid, type_id, owner_uuid, world_name, x, y, z, yaw,
                            fuel_units, locked, health, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    bindState(statement, vehicle);
                    statement.executeUpdate();
                }
                if (storage.length > 0) saveStorage(connection, vehicle.id(), storage, vehicle.updatedAt().toEpochMilli());
                connection.commit();
                return VehicleOperation.success(vehicle);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public Optional<VehicleState> find(UUID vehicleId) throws SQLException {
        String sql = select() + " WHERE vehicle.vehicle_uuid = ?";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, vehicleId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(read(results)) : Optional.empty();
            }
        }
    }

    public List<VehicleState> all() throws SQLException {
        String sql = select() + " ORDER BY vehicle.created_at, vehicle.vehicle_uuid";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            List<VehicleState> vehicles = new ArrayList<>();
            while (results.next()) vehicles.add(read(results));
            return List.copyOf(vehicles);
        }
    }

    public List<VehicleState> owned(UUID ownerId) throws SQLException {
        String sql = select() + " WHERE vehicle.owner_uuid = ? ORDER BY vehicle.created_at, vehicle.vehicle_uuid";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<VehicleState> vehicles = new ArrayList<>();
                while (results.next()) vehicles.add(read(results));
                return List.copyOf(vehicles);
            }
        }
    }

    public VehicleOperation<VehicleState> setLocked(
            UUID vehicleId, UUID actor, boolean locked, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            VehicleState state = find(connection, vehicleId);
            if (state == null) return VehicleOperation.result(VehicleResult.VEHICLE_NOT_FOUND);
            if (!state.ownerId().equals(actor)) return VehicleOperation.result(VehicleResult.NOT_OWNER);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE vehicles SET locked = ?, updated_at = ? WHERE vehicle_uuid = ?")) {
                statement.setInt(1, locked ? 1 : 0);
                statement.setLong(2, now);
                statement.setString(3, vehicleId.toString());
                statement.executeUpdate();
            }
            return VehicleOperation.success(state.withLocked(locked, Instant.ofEpochMilli(now)));
        }
    }

    public VehicleOperation<VehicleState> refuel(
            UUID vehicleId, UUID actor, long amount, long maximumFuel, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            VehicleState state = find(connection, vehicleId);
            if (state == null) return VehicleOperation.result(VehicleResult.VEHICLE_NOT_FOUND);
            if (!state.ownerId().equals(actor)) return VehicleOperation.result(VehicleResult.NOT_OWNER);
            if (state.fuel() >= maximumFuel) return VehicleOperation.result(VehicleResult.FUEL_FULL);
            long fuel = Math.min(maximumFuel, Math.addExact(state.fuel(), amount));
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE vehicles SET fuel_units = ?, updated_at = ? WHERE vehicle_uuid = ?")) {
                statement.setLong(1, fuel);
                statement.setLong(2, now);
                statement.setString(3, vehicleId.toString());
                statement.executeUpdate();
            }
            return VehicleOperation.success(state.withTelemetry(state.worldName(), state.x(), state.y(),
                    state.z(), state.yaw(), fuel, state.health(), Instant.ofEpochMilli(now)));
        }
    }

    public VehicleOperation<VehicleState> repair(
            UUID vehicleId, UUID actor, int amount, int maximumHealth, long now)
            throws SQLException {
        try (Connection connection = database.openConnection()) {
            VehicleState state = find(connection, vehicleId);
            if (state == null) return VehicleOperation.result(VehicleResult.VEHICLE_NOT_FOUND);
            if (!state.ownerId().equals(actor) && !isMechanic(connection, actor)) {
                return VehicleOperation.result(VehicleResult.NOT_OWNER);
            }
            if (state.health() >= maximumHealth) return VehicleOperation.result(VehicleResult.HEALTH_FULL);
            int health = Math.min(maximumHealth, Math.addExact(state.health(), amount));
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE vehicles SET health = ?, updated_at = ? WHERE vehicle_uuid = ?")) {
                statement.setInt(1, health);
                statement.setLong(2, now);
                statement.setString(3, vehicleId.toString());
                statement.executeUpdate();
            }
            return VehicleOperation.success(state.withTelemetry(state.worldName(), state.x(), state.y(),
                    state.z(), state.yaw(), state.fuel(), health, Instant.ofEpochMilli(now)));
        }
    }

    public VehicleOperation<VehicleState> transfer(
            UUID vehicleId, UUID owner, UUID newOwner, int maximumOwned, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                VehicleState state = find(connection, vehicleId);
                if (state == null) {
                    connection.rollback();
                    return VehicleOperation.result(VehicleResult.VEHICLE_NOT_FOUND);
                }
                if (!state.ownerId().equals(owner)) {
                    connection.rollback();
                    return VehicleOperation.result(VehicleResult.NOT_OWNER);
                }
                if (owner.equals(newOwner)) {
                    connection.rollback();
                    return VehicleOperation.result(VehicleResult.SELF_TRANSFER);
                }
                if (!citizenExists(connection, newOwner)) {
                    connection.rollback();
                    return VehicleOperation.result(VehicleResult.CITIZEN_NOT_FOUND);
                }
                if (ownedCount(connection, newOwner) >= maximumOwned) {
                    connection.rollback();
                    return VehicleOperation.result(VehicleResult.OWNER_LIMIT_REACHED);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE vehicles SET owner_uuid = ?, locked = 1, updated_at = ? WHERE vehicle_uuid = ?")) {
                    statement.setString(1, newOwner.toString());
                    statement.setLong(2, now);
                    statement.setString(3, vehicleId.toString());
                    statement.executeUpdate();
                }
                connection.commit();
                VehicleState transferred = find(connection, vehicleId);
                return VehicleOperation.success(transferred == null ? state : transferred);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public VehicleOperation<VehiclePickup> remove(UUID vehicleId, UUID owner) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                VehicleState state = find(connection, vehicleId);
                if (state == null) {
                    connection.rollback();
                    return VehicleOperation.result(VehicleResult.VEHICLE_NOT_FOUND);
                }
                if (!state.ownerId().equals(owner)) {
                    connection.rollback();
                    return VehicleOperation.result(VehicleResult.NOT_OWNER);
                }
                byte[] storage = storage(connection, vehicleId);
                delete(connection, vehicleId);
                connection.commit();
                return VehicleOperation.success(new VehiclePickup(state, storage));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public Optional<VehicleState> destroy(UUID vehicleId) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                VehicleState state = find(connection, vehicleId);
                if (state != null) delete(connection, vehicleId);
                connection.commit();
                return Optional.ofNullable(state);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public void updateTelemetry(VehicleState state) throws SQLException {
        if (!valid(state)) throw new IllegalArgumentException("Invalid vehicle telemetry");
        String sql = """
                UPDATE vehicles SET world_name = ?, x = ?, y = ?, z = ?, yaw = ?,
                    fuel_units = ?, health = ?, updated_at = ? WHERE vehicle_uuid = ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state.worldName());
            statement.setDouble(2, state.x());
            statement.setDouble(3, state.y());
            statement.setDouble(4, state.z());
            statement.setFloat(5, state.yaw());
            statement.setLong(6, state.fuel());
            statement.setInt(7, state.health());
            statement.setLong(8, state.updatedAt().toEpochMilli());
            statement.setString(9, state.id().toString());
            statement.executeUpdate();
        }
    }

    public byte[] storage(UUID vehicleId) throws SQLException {
        try (Connection connection = database.openConnection()) {
            return storage(connection, vehicleId);
        }
    }

    public void saveStorage(UUID vehicleId, byte[] contents, long now) throws SQLException {
        if (contents == null || contents.length > 1_048_576) {
            throw new IllegalArgumentException("Vehicle storage exceeds one mebibyte");
        }
        try (Connection connection = database.openConnection()) {
            saveStorage(connection, vehicleId, contents, now);
        }
    }

    public VehicleAccess access(UUID playerId, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            boolean mechanic = isMechanic(connection, playerId);
            Set<String> licenses = new HashSet<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT license_id FROM licenses
                    WHERE player_uuid = ? AND (expires_at IS NULL OR expires_at > ?)
                    """)) {
                statement.setString(1, playerId.toString());
                statement.setLong(2, now);
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) licenses.add(results.getString(1));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT qualification_id FROM qualifications
                    WHERE player_uuid = ? AND qualification_id IN ('driver', 'pilot')
                    """)) {
                statement.setString(1, playerId.toString());
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) licenses.add(results.getString(1));
                }
            }
            return new VehicleAccess(mechanic, licenses);
        }
    }

    private static String select() {
        return """
                SELECT vehicle.vehicle_uuid, vehicle.type_id, vehicle.owner_uuid,
                    owner.last_name AS owner_name, vehicle.world_name, vehicle.x, vehicle.y, vehicle.z,
                    vehicle.yaw, vehicle.fuel_units, vehicle.locked, vehicle.health,
                    vehicle.created_at, vehicle.updated_at
                FROM vehicles vehicle JOIN players owner ON owner.uuid = vehicle.owner_uuid
                """;
    }

    private static VehicleState find(Connection connection, UUID vehicleId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(select() + " WHERE vehicle.vehicle_uuid = ?")) {
            statement.setString(1, vehicleId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? read(results) : null;
            }
        }
    }

    private static VehicleState read(ResultSet results) throws SQLException {
        return new VehicleState(
                UUID.fromString(results.getString("vehicle_uuid")),
                results.getString("type_id"),
                UUID.fromString(results.getString("owner_uuid")),
                results.getString("owner_name"),
                results.getString("world_name"),
                results.getDouble("x"), results.getDouble("y"), results.getDouble("z"),
                results.getFloat("yaw"), results.getLong("fuel_units"),
                results.getInt("locked") != 0, results.getInt("health"),
                Instant.ofEpochMilli(results.getLong("created_at")),
                Instant.ofEpochMilli(results.getLong("updated_at")));
    }

    private static void bindState(PreparedStatement statement, VehicleState state) throws SQLException {
        statement.setString(1, state.id().toString());
        statement.setString(2, state.typeId());
        statement.setString(3, state.ownerId().toString());
        statement.setString(4, state.worldName());
        statement.setDouble(5, state.x());
        statement.setDouble(6, state.y());
        statement.setDouble(7, state.z());
        statement.setFloat(8, state.yaw());
        statement.setLong(9, state.fuel());
        statement.setInt(10, state.locked() ? 1 : 0);
        statement.setInt(11, state.health());
        statement.setLong(12, state.createdAt().toEpochMilli());
        statement.setLong(13, state.updatedAt().toEpochMilli());
    }

    private static boolean valid(VehicleState state) {
        return state != null && state.id() != null && state.ownerId() != null
                && state.typeId() != null && !state.typeId().isBlank()
                && state.worldName() != null && !state.worldName().isBlank()
                && Double.isFinite(state.x()) && Double.isFinite(state.y()) && Double.isFinite(state.z())
                && Float.isFinite(state.yaw()) && state.fuel() >= 0 && state.health() >= 0
                && state.createdAt() != null && state.updatedAt() != null;
    }

    private static boolean citizenExists(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static int ownedCount(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM vehicles WHERE owner_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        }
    }

    private static boolean isMechanic(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM citizen_jobs WHERE player_uuid = ? AND job_id = 'mechanic'
                UNION SELECT 1 FROM qualifications WHERE player_uuid = ? AND qualification_id = 'mechanic'
                LIMIT 1
                """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static void delete(Connection connection, UUID vehicleId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM vehicles WHERE vehicle_uuid = ?")) {
            statement.setString(1, vehicleId.toString());
            statement.executeUpdate();
        }
    }

    private static byte[] storage(Connection connection, UUID vehicleId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT contents FROM vehicle_storage WHERE vehicle_uuid = ?")) {
            statement.setString(1, vehicleId.toString());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) return new byte[0];
                byte[] contents = results.getBytes(1);
                return contents == null ? new byte[0] : contents;
            }
        }
    }

    private static void saveStorage(
            Connection connection, UUID vehicleId, byte[] contents, long now) throws SQLException {
        String sql = """
                INSERT INTO vehicle_storage(vehicle_uuid, contents, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(vehicle_uuid) DO UPDATE SET contents = excluded.contents, updated_at = excluded.updated_at
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, vehicleId.toString());
            statement.setBytes(2, contents);
            statement.setLong(3, now);
            statement.executeUpdate();
        }
    }
}
