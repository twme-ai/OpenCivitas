package dev.opencivitas.job;

import dev.opencivitas.database.Database;
import dev.opencivitas.economy.LedgerEntryType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class JobEarningRepository {
    private final Database database;

    public JobEarningRepository(Database database) {
        this.database = database;
    }

    public List<JobBlockPosition> placedBlocks() throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT world_name, block_x, block_y, block_z
                     FROM job_placed_blocks
                     ORDER BY world_name, block_x, block_y, block_z
                     """);
             ResultSet results = statement.executeQuery()) {
            List<JobBlockPosition> positions = new ArrayList<>();
            while (results.next()) {
                positions.add(new JobBlockPosition(
                        results.getString("world_name"),
                        results.getInt("block_x"),
                        results.getInt("block_y"),
                        results.getInt("block_z")));
            }
            return List.copyOf(positions);
        }
    }

    public void markPlacedBlock(
            JobBlockPosition position, String materialKey, UUID placedBy, long now) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO job_placed_blocks(
                         world_name, block_x, block_y, block_z, material_key, placed_by, placed_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT(world_name, block_x, block_y, block_z) DO UPDATE SET
                         material_key = excluded.material_key,
                         placed_by = excluded.placed_by,
                         placed_at = excluded.placed_at
                     """)) {
            setPosition(statement, position, 1);
            statement.setString(5, materialKey);
            statement.setString(6, placedBy.toString());
            statement.setLong(7, now);
            statement.executeUpdate();
        }
    }

    public void moveBlocks(List<JobBlockMove> moves, long now) throws SQLException {
        if (moves.isEmpty()) return;
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement remove = connection.prepareStatement("""
                         DELETE FROM job_placed_blocks
                         WHERE world_name = ? AND block_x = ? AND block_y = ? AND block_z = ?
                         """);
                 PreparedStatement insert = connection.prepareStatement("""
                         INSERT INTO job_placed_blocks(
                             world_name, block_x, block_y, block_z, material_key, placed_by, placed_at)
                         VALUES (?, ?, ?, ?, ?, NULL, ?)
                         ON CONFLICT(world_name, block_x, block_y, block_z) DO UPDATE SET
                             material_key = excluded.material_key,
                             placed_by = NULL,
                             placed_at = excluded.placed_at
                         """)) {
                for (JobBlockMove move : moves) {
                    setPosition(remove, move.source(), 1);
                    remove.addBatch();
                }
                remove.executeBatch();
                for (JobBlockMove move : moves) {
                    setPosition(insert, move.destination(), 1);
                    insert.setString(5, move.materialKey());
                    insert.setLong(6, now);
                    insert.addBatch();
                }
                insert.executeBatch();
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public void clearPlacedBlocks(List<JobBlockPosition> positions) throws SQLException {
        if (positions.isEmpty()) return;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM job_placed_blocks
                     WHERE world_name = ? AND block_x = ? AND block_y = ? AND block_z = ?
                     """)) {
            for (JobBlockPosition position : positions) {
                setPosition(statement, position, 1);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public JobEarningAccrual accrueBreak(
            UUID player,
            JobBlockPosition position,
            List<JobEarningCandidate> candidates,
            long occurredAt,
            long payableAt
    ) throws SQLException {
        return accrueBreak(player, position, candidates, occurredAt, payableAt, false);
    }

    public JobEarningAccrual accrueBreak(
            UUID player,
            JobBlockPosition position,
            List<JobEarningCandidate> candidates,
            long occurredAt,
            long payableAt,
            boolean knownPlacedBlock
    ) throws SQLException {
        validateTimes(occurredAt, payableAt);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean persistedPlacedBlock = clearPlacedBlock(connection, position);
                if (knownPlacedBlock || persistedPlacedBlock) {
                    connection.commit();
                    return JobEarningAccrual.blocked(payableAt);
                }
                JobEarningAccrual accrual = accrue(
                        connection, player, candidates, occurredAt, payableAt);
                connection.commit();
                return accrual;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public JobEarningAccrual accrue(
            UUID player,
            List<JobEarningCandidate> candidates,
            long occurredAt,
            long payableAt
    ) throws SQLException {
        validateTimes(occurredAt, payableAt);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                JobEarningAccrual accrual = accrue(
                        connection, player, candidates, occurredAt, payableAt);
                connection.commit();
                return accrual;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<JobPayout> settleDue(long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                List<DuePayout> due = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT player_uuid, SUM(amount_cents) AS amount_cents, COUNT(*) AS action_count
                        FROM job_earning_events
                        WHERE paid_at IS NULL AND payable_at <= ?
                        GROUP BY player_uuid ORDER BY player_uuid
                        """)) {
                    statement.setLong(1, now);
                    try (ResultSet results = statement.executeQuery()) {
                        while (results.next()) {
                            due.add(new DuePayout(
                                    UUID.fromString(results.getString("player_uuid")),
                                    results.getLong("amount_cents"),
                                    Math.toIntExact(results.getLong("action_count"))));
                        }
                    }
                }
                List<JobPayout> payouts = new ArrayList<>();
                for (DuePayout payout : due) {
                    credit(connection, payout.playerId(), payout.amountCents());
                    insertLedger(connection, payout.playerId(), payout.amountCents(), now);
                    markPaid(connection, payout.playerId(), now);
                    payouts.add(new JobPayout(
                            payout.playerId(), payout.amountCents(), payout.actionCount(),
                            balance(connection, payout.playerId())));
                }
                connection.commit();
                return List.copyOf(payouts);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static JobEarningAccrual accrue(
            Connection connection,
            UUID player,
            List<JobEarningCandidate> candidates,
            long occurredAt,
            long payableAt
    ) throws SQLException {
        long total = 0;
        int count = 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO job_earning_events(
                    player_uuid, job_id, action_type, target_key,
                    amount_cents, occurred_at, payable_at)
                SELECT ?, ?, ?, ?, ?, ?, ?
                WHERE EXISTS (
                    SELECT 1 FROM citizen_jobs WHERE player_uuid = ? AND job_id = ?)
                """)) {
            for (JobEarningCandidate candidate : candidates) {
                statement.setString(1, player.toString());
                statement.setString(2, candidate.jobId());
                statement.setString(3, candidate.actionType().name());
                statement.setString(4, candidate.targetKey());
                statement.setLong(5, candidate.amountCents());
                statement.setLong(6, occurredAt);
                statement.setLong(7, payableAt);
                statement.setString(8, player.toString());
                statement.setString(9, candidate.jobId());
                if (statement.executeUpdate() == 1) {
                    total = Math.addExact(total, candidate.amountCents());
                    count++;
                }
            }
        }
        return new JobEarningAccrual(total, count, payableAt, false);
    }

    private static boolean clearPlacedBlock(Connection connection, JobBlockPosition position)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM job_placed_blocks
                WHERE world_name = ? AND block_x = ? AND block_y = ? AND block_z = ?
                """)) {
            setPosition(statement, position, 1);
            return statement.executeUpdate() == 1;
        }
    }

    private static void credit(Connection connection, UUID player, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance_cents = balance_cents + ? WHERE player_uuid = ?")) {
            statement.setLong(1, amount);
            statement.setString(2, player.toString());
            if (statement.executeUpdate() != 1) throw new SQLException("Job earning account was not found");
        }
    }

    private static void insertLedger(Connection connection, UUID player, long amount, long now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger_entries(
                    player_uuid, counterparty_uuid, amount_cents, entry_type, created_at)
                VALUES (?, NULL, ?, ?, ?)
                """)) {
            statement.setString(1, player.toString());
            statement.setLong(2, amount);
            statement.setString(3, LedgerEntryType.JOB_EARNING.name());
            statement.setLong(4, now);
            statement.executeUpdate();
        }
    }

    private static void markPaid(Connection connection, UUID player, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE job_earning_events SET paid_at = ?
                WHERE player_uuid = ? AND paid_at IS NULL AND payable_at <= ?
                """)) {
            statement.setLong(1, now);
            statement.setString(2, player.toString());
            statement.setLong(3, now);
            statement.executeUpdate();
        }
    }

    private static long balance(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_cents FROM accounts WHERE player_uuid = ?")) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) throw new SQLException("Job earning account was not found");
                return results.getLong(1);
            }
        }
    }

    private static int setPosition(
            PreparedStatement statement, JobBlockPosition position, int start) throws SQLException {
        statement.setString(start, position.worldName());
        statement.setInt(start + 1, position.x());
        statement.setInt(start + 2, position.y());
        statement.setInt(start + 3, position.z());
        return start + 4;
    }

    private static void validateTimes(long occurredAt, long payableAt) {
        if (occurredAt < 0 || payableAt < occurredAt) {
            throw new IllegalArgumentException("Job earning times are invalid");
        }
    }

    private record DuePayout(UUID playerId, long amountCents, int actionCount) {
    }
}
