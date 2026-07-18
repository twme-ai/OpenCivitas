package dev.opencivitas.exam;

import dev.opencivitas.database.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ExamRepository {
    private final Database database;

    public ExamRepository(Database database) {
        this.database = database;
    }

    public ExamRecordResult record(
            UUID playerId,
            String examId,
            String qualification,
            int score,
            int total,
            boolean passed
    ) throws SQLException {
        if (total < 1 || score < 0 || score > total) {
            throw new IllegalArgumentException("Exam score must be between zero and the question count");
        }
        long now = Instant.now().toEpochMilli();
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                insertAttempt(connection, playerId, examId, qualification, score, total, passed, now);
                boolean granted = passed && grantQualification(connection, playerId, qualification, now);
                connection.commit();
                return new ExamRecordResult(granted);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<ExamAttempt> attempts(UUID playerId) throws SQLException {
        String sql = """
                SELECT id, exam_id, qualification_id, score, total_questions, passed, completed_at
                FROM exam_attempts
                WHERE player_uuid = ?
                ORDER BY completed_at DESC, id DESC
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<ExamAttempt> attempts = new ArrayList<>();
                while (results.next()) {
                    attempts.add(new ExamAttempt(
                            results.getLong("id"),
                            results.getString("exam_id"),
                            results.getString("qualification_id"),
                            results.getInt("score"),
                            results.getInt("total_questions"),
                            results.getBoolean("passed"),
                            Instant.ofEpochMilli(results.getLong("completed_at"))
                    ));
                }
                return List.copyOf(attempts);
            }
        }
    }

    private static void insertAttempt(
            Connection connection,
            UUID playerId,
            String examId,
            String qualification,
            int score,
            int total,
            boolean passed,
            long now
    ) throws SQLException {
        String sql = """
                INSERT INTO exam_attempts(
                    player_uuid, exam_id, qualification_id, score, total_questions, passed, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, examId);
            statement.setString(3, qualification);
            statement.setInt(4, score);
            statement.setInt(5, total);
            statement.setBoolean(6, passed);
            statement.setLong(7, now);
            statement.executeUpdate();
        }
    }

    private static boolean grantQualification(
            Connection connection,
            UUID playerId,
            String qualification,
            long now
    ) throws SQLException {
        String sql = """
                INSERT OR IGNORE INTO qualifications(player_uuid, qualification_id, granted_by, granted_at)
                VALUES (?, ?, NULL, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.NO_GENERATED_KEYS)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, qualification);
            statement.setLong(3, now);
            return statement.executeUpdate() == 1;
        }
    }
}
