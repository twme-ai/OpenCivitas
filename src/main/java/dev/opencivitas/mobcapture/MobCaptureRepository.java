package dev.opencivitas.mobcapture;

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
import java.util.Set;
import java.util.UUID;

public final class MobCaptureRepository {
    private final Database database;

    public MobCaptureRepository(Database database) {
        this.database = database;
    }

    public MobCaptureAuthorization authorize(
            UUID actorId,
            String actorName,
            UUID targetId,
            String entityType,
            Set<String> eligibleJobs,
            String world,
            double x,
            double y,
            double z,
            long feeCents,
            boolean successfulRoll,
            long now
    ) throws SQLException {
        if (eligibleJobs.isEmpty() || feeCents <= 0) {
            throw new IllegalArgumentException("Capture jobs and a positive fee are required");
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                String job = matchingJob(connection, actorId, eligibleJobs);
                if (job == null) {
                    connection.rollback();
                    return MobCaptureAuthorization.failed(MobCaptureResult.NOT_QUALIFIED, balance(connection, actorId));
                }
                if (!successfulRoll) {
                    connection.rollback();
                    return MobCaptureAuthorization.failed(MobCaptureResult.CHANCE_FAILED, balance(connection, actorId));
                }
                if (targetExists(connection, targetId)) {
                    connection.rollback();
                    return MobCaptureAuthorization.failed(MobCaptureResult.DUPLICATE_TARGET, balance(connection, actorId));
                }
                int debited = debit(connection, actorId, feeCents);
                if (debited == 0) {
                    boolean exists = accountExists(connection, actorId);
                    long balance = exists ? balance(connection, actorId) : 0;
                    connection.rollback();
                    return MobCaptureAuthorization.failed(
                            exists ? MobCaptureResult.INSUFFICIENT_FUNDS : MobCaptureResult.ACCOUNT_NOT_FOUND,
                            balance);
                }
                insertLedger(connection, actorId, -feeCents, LedgerEntryType.MOB_CAPTURE_FEE, now);
                long auditId = insertAudit(
                        connection, actorId, actorName, targetId, entityType, job,
                        world, x, y, z, feeCents, now);
                long balance = balance(connection, actorId);
                connection.commit();
                return MobCaptureAuthorization.success(auditId, balance, job);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public boolean complete(long auditId, long now) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE mob_capture_audit
                     SET status = 'SUCCESS', completed_at = ?, failure_reason = NULL
                     WHERE id = ? AND status = 'PENDING'
                     """)) {
            statement.setLong(1, now);
            statement.setLong(2, auditId);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean refund(long auditId, String reason, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                PendingCapture pending = pending(connection, auditId);
                if (pending == null) {
                    connection.rollback();
                    return false;
                }
                credit(connection, pending.actorId(), pending.feeCents());
                insertLedger(connection, pending.actorId(), pending.feeCents(),
                        LedgerEntryType.MOB_CAPTURE_REFUND, now);
                markRefunded(connection, auditId, reason, now);
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public int recoverPending(long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                List<PendingCapture> pending = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id, actor_uuid, fee_cents FROM mob_capture_audit
                        WHERE status = 'PENDING' ORDER BY id
                        """); ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        pending.add(new PendingCapture(
                                results.getLong("id"),
                                UUID.fromString(results.getString("actor_uuid")),
                                results.getLong("fee_cents")));
                    }
                }
                for (PendingCapture capture : pending) {
                    credit(connection, capture.actorId(), capture.feeCents());
                    insertLedger(connection, capture.actorId(), capture.feeCents(),
                            LedgerEntryType.MOB_CAPTURE_REFUND, now);
                    markRefunded(connection, capture.id(), "startup-recovery", now);
                }
                connection.commit();
                return pending.size();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<MobCaptureRecord> logs(UUID actorId, int limit, int offset) throws SQLException {
        String filter = actorId == null ? "" : " WHERE actor_uuid = ?";
        String sql = """
                SELECT * FROM mob_capture_audit%s
                ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                """.formatted(filter);
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (actorId != null) statement.setString(index++, actorId.toString());
            statement.setInt(index++, limit);
            statement.setInt(index, offset);
            try (ResultSet results = statement.executeQuery()) {
                List<MobCaptureRecord> records = new ArrayList<>();
                while (results.next()) {
                    records.add(new MobCaptureRecord(
                            results.getLong("id"),
                            UUID.fromString(results.getString("actor_uuid")),
                            results.getString("actor_name"),
                            UUID.fromString(results.getString("target_uuid")),
                            results.getString("entity_type"),
                            results.getString("job_id"),
                            results.getString("world"),
                            results.getDouble("x"),
                            results.getDouble("y"),
                            results.getDouble("z"),
                            results.getLong("fee_cents"),
                            results.getString("status"),
                            Instant.ofEpochMilli(results.getLong("created_at"))));
                }
                return List.copyOf(records);
            }
        }
    }

    private static String matchingJob(Connection connection, UUID actorId, Set<String> jobs)
            throws SQLException {
        String placeholders = String.join(",", java.util.Collections.nCopies(jobs.size(), "?"));
        String sql = "SELECT job_id FROM citizen_jobs WHERE player_uuid = ? AND job_id IN ("
                + placeholders + ") ORDER BY job_id LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, actorId.toString());
            int index = 2;
            for (String job : jobs) statement.setString(index++, job);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getString(1) : null;
            }
        }
    }

    private static boolean targetExists(Connection connection, UUID targetId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM mob_capture_audit "
                        + "WHERE target_uuid = ? AND status IN ('PENDING', 'SUCCESS')")) {
            statement.setString(1, targetId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static int debit(Connection connection, UUID actorId, long feeCents) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE accounts SET balance_cents = balance_cents - ?
                WHERE player_uuid = ? AND balance_cents >= ?
                """)) {
            statement.setLong(1, feeCents);
            statement.setString(2, actorId.toString());
            statement.setLong(3, feeCents);
            return statement.executeUpdate();
        }
    }

    private static void credit(Connection connection, UUID actorId, long feeCents) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance_cents = balance_cents + ? WHERE player_uuid = ?")) {
            statement.setLong(1, feeCents);
            statement.setString(2, actorId.toString());
            if (statement.executeUpdate() != 1) throw new SQLException("Capture refund account was not found");
        }
    }

    private static boolean accountExists(Connection connection, UUID actorId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM accounts WHERE player_uuid = ?")) {
            statement.setString(1, actorId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static long balance(Connection connection, UUID actorId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_cents FROM accounts WHERE player_uuid = ?")) {
            statement.setString(1, actorId.toString());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) return 0;
                return results.getLong(1);
            }
        }
    }

    private static void insertLedger(
            Connection connection,
            UUID actorId,
            long amountCents,
            LedgerEntryType type,
            long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger_entries(player_uuid, amount_cents, entry_type, created_at)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, actorId.toString());
            statement.setLong(2, amountCents);
            statement.setString(3, type.name());
            statement.setLong(4, now);
            statement.executeUpdate();
        }
    }

    private static long insertAudit(
            Connection connection,
            UUID actorId,
            String actorName,
            UUID targetId,
            String entityType,
            String jobId,
            String world,
            double x,
            double y,
            double z,
            long feeCents,
            long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO mob_capture_audit(
                    actor_uuid, actor_name, target_uuid, entity_type, job_id,
                    world, x, y, z, fee_cents, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, actorId.toString());
            statement.setString(2, actorName);
            statement.setString(3, targetId.toString());
            statement.setString(4, entityType);
            statement.setString(5, jobId);
            statement.setString(6, world);
            statement.setDouble(7, x);
            statement.setDouble(8, y);
            statement.setDouble(9, z);
            statement.setLong(10, feeCents);
            statement.setLong(11, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Capture audit id was not generated");
                return keys.getLong(1);
            }
        }
    }

    private static PendingCapture pending(Connection connection, long auditId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, actor_uuid, fee_cents FROM mob_capture_audit
                WHERE id = ? AND status = 'PENDING'
                """)) {
            statement.setLong(1, auditId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) return null;
                return new PendingCapture(
                        results.getLong("id"),
                        UUID.fromString(results.getString("actor_uuid")),
                        results.getLong("fee_cents"));
            }
        }
    }

    private static void markRefunded(Connection connection, long auditId, String reason, long now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE mob_capture_audit
                SET status = 'REFUNDED', failure_reason = ?, completed_at = ?
                WHERE id = ? AND status = 'PENDING'
                """)) {
            statement.setString(1, reason);
            statement.setLong(2, now);
            statement.setLong(3, auditId);
            if (statement.executeUpdate() != 1) throw new SQLException("Capture refund state changed");
        }
    }

    private record PendingCapture(long id, UUID actorId, long feeCents) {
    }
}
