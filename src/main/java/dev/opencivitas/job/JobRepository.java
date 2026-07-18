package dev.opencivitas.job;

import dev.opencivitas.database.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class JobRepository {
    private final Database database;

    public JobRepository(Database database) {
        this.database = database;
    }

    public List<CitizenJob> jobs(UUID playerId) throws SQLException {
        String sql = """
                SELECT job_id, category, joined_at, appointed_by
                FROM citizen_jobs
                WHERE player_uuid = ?
                ORDER BY category, joined_at, job_id
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<CitizenJob> jobs = new ArrayList<>();
                while (results.next()) {
                    jobs.add(new CitizenJob(
                            results.getString("job_id"),
                            JobCategory.valueOf(results.getString("category")),
                            Instant.ofEpochMilli(results.getLong("joined_at")),
                            results.getString("appointed_by")
                    ));
                }
                return List.copyOf(jobs);
            }
        }
    }

    public List<String> qualifications(UUID playerId) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT qualification_id FROM qualifications WHERE player_uuid = ? "
                             + "ORDER BY qualification_id")) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<String> qualifications = new ArrayList<>();
                while (results.next()) {
                    qualifications.add(results.getString(1));
                }
                return List.copyOf(qualifications);
            }
        }
    }

    public List<CitizenLicense> licenses(UUID playerId, long now) throws SQLException {
        String sql = """
                SELECT license_id, granted_at, expires_at FROM licenses
                WHERE player_uuid = ? AND (expires_at IS NULL OR expires_at > ?)
                ORDER BY license_id
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setLong(2, now);
            try (ResultSet results = statement.executeQuery()) {
                List<CitizenLicense> licenses = new ArrayList<>();
                while (results.next()) {
                    long expires = results.getLong("expires_at");
                    boolean permanent = results.wasNull();
                    licenses.add(new CitizenLicense(
                            results.getString("license_id"),
                            Instant.ofEpochMilli(results.getLong("granted_at")),
                            permanent ? null : Instant.ofEpochMilli(expires)));
                }
                return List.copyOf(licenses);
            }
        }
    }

    public boolean grantLicense(
            UUID playerId, String license, UUID grantedBy, Long expiresAt, long now) throws SQLException {
        String sql = """
                INSERT INTO licenses(player_uuid, license_id, granted_by, granted_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(player_uuid, license_id) DO UPDATE SET
                    granted_by = excluded.granted_by,
                    granted_at = excluded.granted_at,
                    expires_at = excluded.expires_at
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            if (grantedBy == null) statement.setNull(3, java.sql.Types.VARCHAR);
            else statement.setString(3, grantedBy.toString());
            statement.setString(2, license);
            statement.setLong(4, now);
            if (expiresAt == null) statement.setNull(5, java.sql.Types.BIGINT);
            else statement.setLong(5, expiresAt);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean revokeLicense(UUID playerId, String license) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM licenses WHERE player_uuid = ? AND license_id = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, license);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean setPrefix(UUID playerId, String jobId, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            if (jobId == null) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM player_prefixes WHERE player_uuid = ?")) {
                    statement.setString(1, playerId.toString());
                    statement.executeUpdate();
                    return true;
                }
            }
            if (!hasJob(connection, playerId, jobId)) {
                return false;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO player_prefixes(player_uuid, job_id, selected_at) VALUES (?, ?, ?)
                    ON CONFLICT(player_uuid) DO UPDATE SET job_id = excluded.job_id, selected_at = excluded.selected_at
                    """)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, jobId);
                statement.setLong(3, now);
                statement.executeUpdate();
                return true;
            }
        }
    }

    public Optional<String> prefix(UUID playerId) throws SQLException {
        String sql = """
                SELECT prefix.job_id FROM player_prefixes prefix
                JOIN citizen_jobs job ON job.player_uuid = prefix.player_uuid AND job.job_id = prefix.job_id
                WHERE prefix.player_uuid = ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(results.getString(1)) : Optional.empty();
            }
        }
    }

    public boolean grantQualification(UUID playerId, String qualification, UUID grantedBy) throws SQLException {
        String sql = """
                INSERT OR IGNORE INTO qualifications(player_uuid, qualification_id, granted_by, granted_at)
                VALUES (?, ?, ?, ?)
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, qualification);
            if (grantedBy == null) {
                statement.setNull(3, java.sql.Types.VARCHAR);
            } else {
                statement.setString(3, grantedBy.toString());
            }
            statement.setLong(4, Instant.now().toEpochMilli());
            return statement.executeUpdate() == 1;
        }
    }

    public boolean revokeQualification(UUID playerId, String qualification) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM qualifications WHERE player_uuid = ? AND qualification_id = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, qualification);
            return statement.executeUpdate() == 1;
        }
    }

    public JobJoinResult join(UUID playerId, JobDefinition job, int categoryLimit) throws SQLException {
        return join(playerId, job, categoryLimit, null, false);
    }

    public JobJoinResult appoint(UUID playerId, JobDefinition job, int categoryLimit, UUID appointedBy)
            throws SQLException {
        return join(playerId, job, categoryLimit, appointedBy, true);
    }

    private JobJoinResult join(
            UUID playerId,
            JobDefinition job,
            int categoryLimit,
            UUID appointedBy,
            boolean appointment
    ) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!citizenExists(connection, playerId)) {
                    connection.rollback();
                    return JobJoinResult.CITIZEN_NOT_FOUND;
                }
                if (hasJob(connection, playerId, job.id())) {
                    connection.rollback();
                    return JobJoinResult.ALREADY_JOINED;
                }
                if (!appointment && !hasQualification(connection, playerId, job.qualification())) {
                    connection.rollback();
                    return JobJoinResult.MISSING_QUALIFICATION;
                }
                if (countCategory(connection, playerId, job.category()) >= categoryLimit) {
                    connection.rollback();
                    return JobJoinResult.CATEGORY_LIMIT;
                }
                insertJob(connection, playerId, job, appointedBy);
                connection.commit();
                return JobJoinResult.SUCCESS;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public boolean leave(UUID playerId, String jobId) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM citizen_jobs WHERE player_uuid = ? AND job_id = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, jobId);
            return statement.executeUpdate() == 1;
        }
    }

    public int leaveCategory(UUID playerId, JobCategory category) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM citizen_jobs WHERE player_uuid = ? AND category = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, category.name());
            return statement.executeUpdate();
        }
    }

    private static boolean citizenExists(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static boolean hasJob(Connection connection, UUID playerId, String jobId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM citizen_jobs WHERE player_uuid = ? AND job_id = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, jobId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static boolean hasQualification(Connection connection, UUID playerId, String qualification)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM qualifications WHERE player_uuid = ? AND qualification_id = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, qualification);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static int countCategory(Connection connection, UUID playerId, JobCategory category)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM citizen_jobs WHERE player_uuid = ? AND category = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, category.name());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        }
    }

    private static void insertJob(Connection connection, UUID playerId, JobDefinition job, UUID appointedBy)
            throws SQLException {
        String sql = """
                INSERT INTO citizen_jobs(player_uuid, job_id, category, joined_at, appointed_by)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, job.id());
            statement.setString(3, job.category().name());
            statement.setLong(4, Instant.now().toEpochMilli());
            if (appointedBy == null) {
                statement.setNull(5, java.sql.Types.VARCHAR);
            } else {
                statement.setString(5, appointedBy.toString());
            }
            statement.executeUpdate();
        }
    }
}
