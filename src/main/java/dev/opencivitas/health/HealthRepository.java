package dev.opencivitas.health;

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

public final class HealthRepository {
    private final Database database;

    public HealthRepository(Database database) {
        this.database = database;
    }

    public HealthOperation<Integer> temperature(UUID patientId, int normalTemperature, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            if (!citizenExists(connection, patientId)) return HealthOperation.result(HealthResult.CITIZEN_NOT_FOUND);
            ensureProfile(connection, patientId, normalTemperature, now);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT temperature_millicelsius FROM health_profiles WHERE player_uuid = ?")) {
                statement.setString(1, patientId.toString());
                try (ResultSet results = statement.executeQuery()) {
                    return results.next() ? HealthOperation.success(results.getInt(1))
                            : HealthOperation.result(HealthResult.CITIZEN_NOT_FOUND);
                }
            }
        }
    }

    public HealthOperation<Integer> updateTemperature(
            UUID patientId, int temperature, int normalTemperature, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            if (!citizenExists(connection, patientId)) return HealthOperation.result(HealthResult.CITIZEN_NOT_FOUND);
            ensureProfile(connection, patientId, normalTemperature, now);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE health_profiles SET temperature_millicelsius = ?, updated_at = ? WHERE player_uuid = ?
                    """)) {
                statement.setInt(1, temperature);
                statement.setLong(2, now);
                statement.setString(3, patientId.toString());
                statement.executeUpdate();
            }
            return HealthOperation.success(temperature);
        }
    }

    public HealthOperation<PatientCondition> expose(
            UUID patientId, HealthConditionDefinition condition, String source, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            if (!citizenExists(connection, patientId)) return HealthOperation.result(HealthResult.CITIZEN_NOT_FOUND);
            String sql = """
                    INSERT OR IGNORE INTO health_conditions(
                        player_uuid, condition_id, source, acquired_at) VALUES (?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, patientId.toString());
                statement.setString(2, condition.id());
                statement.setString(3, source);
                statement.setLong(4, now);
                if (statement.executeUpdate() != 1) return HealthOperation.result(HealthResult.ALREADY_AFFECTED);
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("No generated health condition id");
                    return HealthOperation.success(new PatientCondition(
                            keys.getLong(1), patientId, condition.id(), source, Instant.ofEpochMilli(now), null));
                }
            }
        }
    }

    public List<PatientCondition> activeConditions(UUID patientId) throws SQLException {
        String sql = """
                SELECT id, condition_id, source, acquired_at FROM health_conditions
                WHERE player_uuid = ? AND resolved_at IS NULL ORDER BY acquired_at, id
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, patientId.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<PatientCondition> conditions = new ArrayList<>();
                while (results.next()) conditions.add(new PatientCondition(
                        results.getLong("id"), patientId, results.getString("condition_id"),
                        results.getString("source"), Instant.ofEpochMilli(results.getLong("acquired_at")), null));
                return List.copyOf(conditions);
            }
        }
    }

    public List<PatientCondition> conditionHistory(UUID patientId, int limit) throws SQLException {
        String sql = """
                SELECT id, condition_id, source, acquired_at, resolved_at FROM health_conditions
                WHERE player_uuid = ? ORDER BY acquired_at DESC, id DESC LIMIT ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, patientId.toString());
            statement.setInt(2, limit);
            try (ResultSet results = statement.executeQuery()) {
                List<PatientCondition> conditions = new ArrayList<>();
                while (results.next()) {
                    long resolved = results.getLong("resolved_at");
                    conditions.add(new PatientCondition(results.getLong("id"), patientId,
                            results.getString("condition_id"), results.getString("source"),
                            Instant.ofEpochMilli(results.getLong("acquired_at")),
                            results.wasNull() ? null : Instant.ofEpochMilli(resolved)));
                }
                return List.copyOf(conditions);
            }
        }
    }

    public HealthOperation<MedicalTreatment> treat(
            UUID patientId,
            UUID practitionerId,
            TreatmentDefinition treatment,
            List<String> matchingConditionIds,
            long now
    ) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!citizenExists(connection, patientId) || !citizenExists(connection, practitionerId)) {
                    connection.rollback();
                    return HealthOperation.result(HealthResult.CITIZEN_NOT_FOUND);
                }
                boolean doctor = isDoctor(connection, practitionerId);
                if (treatment.careSetting() == CareSetting.HOSPITAL && !doctor) {
                    connection.rollback();
                    return HealthOperation.result(HealthResult.NOT_DOCTOR);
                }
                ActiveCondition active = firstTreatable(connection, patientId, matchingConditionIds);
                if (active == null) {
                    connection.rollback();
                    return HealthOperation.result(HealthResult.NO_TREATABLE_CONDITION);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE health_conditions SET resolved_at = ? WHERE id = ? AND resolved_at IS NULL")) {
                    statement.setLong(1, now);
                    statement.setLong(2, active.id());
                    if (statement.executeUpdate() != 1) throw new SQLException("Condition changed during treatment");
                }
                boolean medicare = doctor && treatment.careSetting() == CareSetting.HOSPITAL
                        && !patientId.equals(practitionerId);
                String sql = """
                        INSERT INTO medical_treatments(
                            patient_uuid, practitioner_uuid, treatment_id, condition_id, administered_at,
                            medicare_eligible, medicare_benefit_cents)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """;
                long id;
                try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, patientId.toString());
                    statement.setString(2, practitionerId.toString());
                    statement.setString(3, treatment.id());
                    statement.setString(4, active.conditionId());
                    statement.setLong(5, now);
                    statement.setInt(6, medicare ? 1 : 0);
                    statement.setLong(7, medicare ? treatment.medicareBenefitCents() : 0);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("No generated medical treatment id");
                        id = keys.getLong(1);
                    }
                }
                connection.commit();
                return HealthOperation.success(new MedicalTreatment(id, patientId, practitionerId,
                        treatment.id(), active.conditionId(), Instant.ofEpochMilli(now), medicare,
                        medicare ? treatment.medicareBenefitCents() : 0, null));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public HealthOperation<MedicareClaim> bulkBill(
            UUID practitionerId, UUID patientId, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!isDoctor(connection, practitionerId)) {
                    connection.rollback();
                    return HealthOperation.result(HealthResult.NOT_DOCTOR);
                }
                String sql = """
                        SELECT treatment.id, treatment.treatment_id, treatment.medicare_benefit_cents,
                               patient.last_name
                        FROM medical_treatments treatment
                        JOIN players patient ON patient.uuid = treatment.patient_uuid
                        WHERE treatment.practitioner_uuid = ? AND treatment.patient_uuid = ?
                          AND treatment.medicare_eligible = 1 AND treatment.billed_at IS NULL
                        ORDER BY treatment.administered_at DESC, treatment.id DESC LIMIT 1
                        """;
                MedicareClaim claim;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, practitionerId.toString());
                    statement.setString(2, patientId.toString());
                    try (ResultSet results = statement.executeQuery()) {
                        if (!results.next()) {
                            connection.rollback();
                            return HealthOperation.result(HealthResult.NO_MEDICARE_CLAIM);
                        }
                        claim = new MedicareClaim(results.getLong(1), results.getString(2),
                                results.getString(4), results.getLong(3));
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE medical_treatments SET billed_at = ? WHERE id = ? AND billed_at IS NULL")) {
                    statement.setLong(1, now);
                    statement.setLong(2, claim.treatmentRecordId());
                    if (statement.executeUpdate() != 1) throw new SQLException("Medicare claim changed during billing");
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE accounts SET balance_cents = balance_cents + ? WHERE player_uuid = ?")) {
                    statement.setLong(1, claim.benefitCents());
                    statement.setString(2, practitionerId.toString());
                    if (statement.executeUpdate() != 1) {
                        connection.rollback();
                        return HealthOperation.result(HealthResult.ACCOUNT_NOT_FOUND);
                    }
                }
                insertLedger(connection, practitionerId, patientId, claim.benefitCents(),
                        LedgerEntryType.MEDICARE_BENEFIT, now);
                connection.commit();
                return HealthOperation.success(claim);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public HealthOperation<Long> purchaseMedicine(
            UUID patientId, TreatmentDefinition treatment, BlockPosition counter, long now) throws SQLException {
        if (treatment.careSetting() != CareSetting.PHARMACY) {
            return HealthOperation.result(HealthResult.NOT_PHARMACY_TREATMENT);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!isRegisteredBlock(connection, "pharmacy_counters", counter)) {
                    connection.rollback();
                    return HealthOperation.result(HealthResult.PHARMACY_NOT_FOUND);
                }
                long balance;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT balance_cents FROM accounts WHERE player_uuid = ?")) {
                    statement.setString(1, patientId.toString());
                    try (ResultSet results = statement.executeQuery()) {
                        if (!results.next()) {
                            connection.rollback();
                            return HealthOperation.result(HealthResult.ACCOUNT_NOT_FOUND);
                        }
                        balance = results.getLong(1);
                    }
                }
                if (balance < treatment.pharmacyCopayCents()) {
                    connection.rollback();
                    return HealthOperation.result(HealthResult.INSUFFICIENT_FUNDS);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE accounts SET balance_cents = balance_cents - ? WHERE player_uuid = ?")) {
                    statement.setLong(1, treatment.pharmacyCopayCents());
                    statement.setString(2, patientId.toString());
                    if (statement.executeUpdate() != 1) throw new SQLException("Pharmacy account changed");
                }
                if (treatment.pharmacyCopayCents() > 0) insertLedger(connection, patientId, null,
                        -treatment.pharmacyCopayCents(), LedgerEntryType.PHARMACY_COPAY, now);
                connection.commit();
                return HealthOperation.success(balance - treatment.pharmacyCopayCents());
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public HealthOperation<MedicalCall> call(
            UUID patientId, String world, double x, double y, double z, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            if (!citizenExists(connection, patientId)) return HealthOperation.result(HealthResult.CITIZEN_NOT_FOUND);
            MedicalCall existing = activeCall(connection, patientId);
            if (existing != null) return HealthOperation.success(existing);
            String sql = """
                    INSERT INTO medical_calls(patient_uuid, world_name, x, y, z, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, patientId.toString());
                statement.setString(2, world);
                statement.setDouble(3, x);
                statement.setDouble(4, y);
                statement.setDouble(5, z);
                statement.setLong(6, now);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("No generated medical call id");
                    String name = playerName(connection, patientId).orElseThrow();
                    return HealthOperation.success(new MedicalCall(keys.getLong(1), patientId, name,
                            world, x, y, z, MedicalCallStatus.OPEN, null, Instant.ofEpochMilli(now)));
                }
            }
        }
    }

    public HealthOperation<MedicalCall> claimCall(UUID patientId, UUID doctorId, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!isDoctor(connection, doctorId)) {
                    connection.rollback();
                    return HealthOperation.result(HealthResult.NOT_DOCTOR);
                }
                MedicalCall call = activeCall(connection, patientId);
                if (call == null) {
                    connection.rollback();
                    return HealthOperation.result(HealthResult.CALL_NOT_FOUND);
                }
                if (call.status() == MedicalCallStatus.CLAIMED && !doctorId.equals(call.claimedBy())) {
                    connection.rollback();
                    return HealthOperation.result(HealthResult.CALL_ALREADY_CLAIMED);
                }
                if (call.status() == MedicalCallStatus.OPEN) try (PreparedStatement statement =
                        connection.prepareStatement("""
                                UPDATE medical_calls SET status = 'CLAIMED', claimed_by = ?, claimed_at = ?
                                WHERE id = ? AND status = 'OPEN'
                                """)) {
                    statement.setString(1, doctorId.toString());
                    statement.setLong(2, now);
                    statement.setLong(3, call.id());
                    if (statement.executeUpdate() != 1) throw new SQLException("Medical call changed while claimed");
                }
                connection.commit();
                return HealthOperation.success(new MedicalCall(call.id(), call.patientId(), call.patientName(),
                        call.world(), call.x(), call.y(), call.z(), MedicalCallStatus.CLAIMED,
                        doctorId, call.createdAt()));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public boolean markAttended(long callId, UUID doctorId, long now) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE medical_calls SET status = 'ATTENDED', attended_at = ?
                     WHERE id = ? AND status = 'CLAIMED' AND claimed_by = ?
                     """)) {
            statement.setLong(1, now);
            statement.setLong(2, callId);
            statement.setString(3, doctorId.toString());
            return statement.executeUpdate() == 1;
        }
    }

    public boolean releaseCall(long callId, UUID doctorId) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE medical_calls SET status = 'OPEN', claimed_by = NULL, claimed_at = NULL
                     WHERE id = ? AND status = 'CLAIMED' AND claimed_by = ?
                     """)) {
            statement.setLong(1, callId);
            statement.setString(2, doctorId.toString());
            return statement.executeUpdate() == 1;
        }
    }

    public int releaseStaleClaims(long claimedBefore) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE medical_calls SET status = 'OPEN', claimed_by = NULL, claimed_at = NULL
                     WHERE status = 'CLAIMED' AND claimed_at <= ?
                     """)) {
            statement.setLong(1, claimedBefore);
            return statement.executeUpdate();
        }
    }

    public List<MedicalCall> openCalls() throws SQLException {
        String sql = """
                SELECT call.id, call.patient_uuid, patient.last_name, call.world_name,
                       call.x, call.y, call.z, call.status, call.claimed_by, call.created_at
                FROM medical_calls call JOIN players patient ON patient.uuid = call.patient_uuid
                WHERE call.status IN ('OPEN', 'CLAIMED') ORDER BY call.created_at, call.id
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            List<MedicalCall> calls = new ArrayList<>();
            while (results.next()) calls.add(readCall(results));
            return List.copyOf(calls);
        }
    }

    public boolean isDoctor(UUID playerId) throws SQLException {
        try (Connection connection = database.openConnection()) {
            return isDoctor(connection, playerId);
        }
    }

    public List<UUID> doctorIds() throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT DISTINCT player_uuid FROM citizen_jobs
                     WHERE job_id IN ('doctor', 'medical-specialist') ORDER BY player_uuid
                     """); ResultSet results = statement.executeQuery()) {
            List<UUID> doctors = new ArrayList<>();
            while (results.next()) doctors.add(UUID.fromString(results.getString(1)));
            return List.copyOf(doctors);
        }
    }

    public boolean setMonitor(BlockPosition position, UUID actor, long now) throws SQLException {
        String sql = """
                INSERT INTO medical_call_monitors(world_name, x, y, z, registered_by, registered_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(world_name, x, y, z) DO UPDATE SET
                    registered_by = excluded.registered_by, registered_at = excluded.registered_at
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindPosition(statement, position);
            statement.setString(5, actor.toString());
            statement.setLong(6, now);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean removeMonitor(BlockPosition position) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM medical_call_monitors WHERE world_name = ? AND x = ? AND y = ? AND z = ?
                     """)) {
            bindPosition(statement, position);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean isMonitor(BlockPosition position) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT 1 FROM medical_call_monitors WHERE world_name = ? AND x = ? AND y = ? AND z = ?
                     """)) {
            bindPosition(statement, position);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    public boolean setPharmacy(BlockPosition position, UUID actor, long now) throws SQLException {
        return setRegisteredBlock("pharmacy_counters", position, actor, now);
    }

    public boolean removePharmacy(BlockPosition position) throws SQLException {
        return removeRegisteredBlock("pharmacy_counters", position);
    }

    public boolean isPharmacy(BlockPosition position) throws SQLException {
        try (Connection connection = database.openConnection()) {
            return isRegisteredBlock(connection, "pharmacy_counters", position);
        }
    }

    private static ActiveCondition firstTreatable(
            Connection connection, UUID patientId, List<String> conditionIds) throws SQLException {
        if (conditionIds.isEmpty()) return null;
        String placeholders = String.join(",", java.util.Collections.nCopies(conditionIds.size(), "?"));
        String sql = "SELECT id, condition_id FROM health_conditions WHERE player_uuid = ? "
                + "AND resolved_at IS NULL AND condition_id IN (" + placeholders + ") ORDER BY acquired_at, id LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, patientId.toString());
            for (int index = 0; index < conditionIds.size(); index++) statement.setString(index + 2, conditionIds.get(index));
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? new ActiveCondition(results.getLong(1), results.getString(2)) : null;
            }
        }
    }

    private static MedicalCall activeCall(Connection connection, UUID patientId) throws SQLException {
        String sql = """
                SELECT call.id, call.patient_uuid, patient.last_name, call.world_name,
                       call.x, call.y, call.z, call.status, call.claimed_by, call.created_at
                FROM medical_calls call JOIN players patient ON patient.uuid = call.patient_uuid
                WHERE call.patient_uuid = ? AND call.status IN ('OPEN', 'CLAIMED') LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, patientId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? readCall(results) : null;
            }
        }
    }

    private static MedicalCall readCall(ResultSet results) throws SQLException {
        String claimed = results.getString("claimed_by");
        return new MedicalCall(results.getLong("id"), UUID.fromString(results.getString("patient_uuid")),
                results.getString("last_name"), results.getString("world_name"),
                results.getDouble("x"), results.getDouble("y"), results.getDouble("z"),
                MedicalCallStatus.valueOf(results.getString("status")),
                claimed == null ? null : UUID.fromString(claimed),
                Instant.ofEpochMilli(results.getLong("created_at")));
    }

    private static void ensureProfile(
            Connection connection, UUID patientId, int normalTemperature, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO health_profiles(player_uuid, temperature_millicelsius, updated_at)
                VALUES (?, ?, ?)
                """)) {
            statement.setString(1, patientId.toString());
            statement.setInt(2, normalTemperature);
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

    private static boolean isDoctor(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM citizen_jobs WHERE player_uuid = ?
                AND job_id IN ('doctor', 'medical-specialist') LIMIT 1
                """)) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static void insertLedger(
            Connection connection, UUID player, UUID counterparty, long amount,
            LedgerEntryType type, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger_entries(player_uuid, counterparty_uuid, amount_cents, entry_type, created_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, player.toString());
            if (counterparty == null) statement.setNull(2, java.sql.Types.VARCHAR);
            else statement.setString(2, counterparty.toString());
            statement.setLong(3, amount);
            statement.setString(4, type.name());
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private static void bindPosition(PreparedStatement statement, BlockPosition position) throws SQLException {
        statement.setString(1, position.world());
        statement.setInt(2, position.x());
        statement.setInt(3, position.y());
        statement.setInt(4, position.z());
    }

    private boolean setRegisteredBlock(
            String table, BlockPosition position, UUID actor, long now) throws SQLException {
        String sql = "INSERT INTO " + table + "(world_name, x, y, z, registered_by, registered_at) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(world_name, x, y, z) DO UPDATE SET "
                + "registered_by = excluded.registered_by, registered_at = excluded.registered_at";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindPosition(statement, position);
            statement.setString(5, actor.toString());
            statement.setLong(6, now);
            return statement.executeUpdate() == 1;
        }
    }

    private boolean removeRegisteredBlock(String table, BlockPosition position) throws SQLException {
        String sql = "DELETE FROM " + table + " WHERE world_name = ? AND x = ? AND y = ? AND z = ?";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindPosition(statement, position);
            return statement.executeUpdate() == 1;
        }
    }

    private static boolean isRegisteredBlock(
            Connection connection, String table, BlockPosition position) throws SQLException {
        String sql = "SELECT 1 FROM " + table + " WHERE world_name = ? AND x = ? AND y = ? AND z = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindPosition(statement, position);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private record ActiveCondition(long id, String conditionId) {
    }
}
