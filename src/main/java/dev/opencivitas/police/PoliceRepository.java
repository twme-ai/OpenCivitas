package dev.opencivitas.police;

import dev.opencivitas.database.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class PoliceRepository {
    private static final long MAX_FINE_CENTS = 100_000_000_000L;
    private static final long DEATH_ATTACK_WINDOW_MILLIS = Duration.ofSeconds(30).toMillis();
    private static final List<String> LAW_ENFORCEMENT_JOBS =
            List.of("police-officer", "detective", "investigator");

    private final Database database;
    private final long selfDefenseWindowMillis;
    private final long reportWindowMillis;

    public PoliceRepository(Database database, Duration selfDefenseWindow, Duration reportWindow) {
        this.database = database;
        selfDefenseWindowMillis = selfDefenseWindow.toMillis();
        reportWindowMillis = reportWindow.toMillis();
    }

    public boolean consent(UUID player) throws SQLException {
        try (Connection connection = database.openConnection()) {
            return consent(connection, player);
        }
    }

    public boolean toggleConsent(UUID player, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!playerExists(connection, player)) {
                    connection.rollback();
                    return false;
                }
                boolean enabled = !consent(connection, player);
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO pvp_preferences(player_uuid, consent_enabled, updated_at)
                        VALUES (?, ?, ?)
                        ON CONFLICT(player_uuid) DO UPDATE SET
                            consent_enabled = excluded.consent_enabled,
                            updated_at = excluded.updated_at
                        """)) {
                    statement.setString(1, player.toString());
                    statement.setInt(2, enabled ? 1 : 0);
                    statement.setLong(3, now);
                    statement.executeUpdate();
                }
                connection.commit();
                return enabled;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public LawOperation<AttackResult> recordAttack(
            UUID attacker,
            UUID victim,
            String world,
            double x,
            double y,
            double z,
            int damageMillihearts,
            String damageCause,
            byte[] weaponData,
            long now
    ) throws SQLException {
        if (attacker.equals(victim) || world.isBlank() || damageMillihearts < 0
                || damageCause.isBlank()) {
            return LawOperation.failure(LawResult.INVALID_CONTENT);
        }
        try (Connection connection = database.openConnection()) {
            if (!playerExists(connection, attacker) || !playerExists(connection, victim)) {
                return LawOperation.failure(LawResult.PLAYER_NOT_FOUND);
            }
            long cutoff = now - selfDefenseWindowMillis;
            boolean recentAggression = recentUnlawful(connection, attacker, victim, cutoff);
            LegalBasis basis = legalBasis(connection, attacker, victim, cutoff);
            long id = insertIncident(connection, attacker, victim, world, x, y, z,
                    damageMillihearts, damageCause, weaponData, basis, now);
            return LawOperation.success(new AttackResult(
                    readIncident(connection, id).orElseThrow(),
                    basis == LegalBasis.UNLAWFUL && !recentAggression));
        }
    }

    public LawOperation<DeathResult> recordDeath(
            UUID attacker,
            UUID victim,
            String world,
            double x,
            double y,
            double z,
            long now
    ) throws SQLException {
        if (attacker.equals(victim) || world.isBlank()) {
            return LawOperation.failure(LawResult.INVALID_CONTENT);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!playerExists(connection, attacker) || !playerExists(connection, victim)) {
                    return rollback(connection, LawResult.PLAYER_NOT_FOUND);
                }
                Optional<CombatIncident> recent = latestIncident(
                        connection, attacker, victim, now - DEATH_ATTACK_WINDOW_MILLIS);
                long incidentId;
                LegalBasis basis;
                if (recent.isPresent()) {
                    incidentId = recent.get().id();
                    basis = recent.get().legalBasis();
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE combat_incidents SET fatal = 1, death_at = ?,
                                world_name = ?, x = ?, y = ?, z = ?
                            WHERE id = ? AND fatal = 0
                            """)) {
                        statement.setLong(1, now);
                        statement.setString(2, world);
                        statement.setDouble(3, x);
                        statement.setDouble(4, y);
                        statement.setDouble(5, z);
                        statement.setLong(6, incidentId);
                        if (statement.executeUpdate() == 0) {
                            return rollback(connection, LawResult.INVALID_STATE);
                        }
                    }
                } else {
                    basis = legalBasis(connection, attacker, victim, now - selfDefenseWindowMillis);
                    incidentId = insertIncident(connection, attacker, victim, world, x, y, z,
                            0, "PLAYER_KILL", null, basis, now);
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE combat_incidents SET fatal = 1, death_at = ? WHERE id = ?")) {
                        statement.setLong(1, now);
                        statement.setLong(2, incidentId);
                        statement.executeUpdate();
                    }
                }

                ForensicClue clue = null;
                if (basis == LegalBasis.UNLAWFUL) {
                    long clueId;
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO forensic_clues(incident_id, created_at) VALUES (?, ?)
                            """, Statement.RETURN_GENERATED_KEYS)) {
                        statement.setLong(1, incidentId);
                        statement.setLong(2, now);
                        statement.executeUpdate();
                        try (ResultSet keys = statement.getGeneratedKeys()) {
                            if (!keys.next()) throw new SQLException("Clue insert returned no id");
                            clueId = keys.getLong(1);
                        }
                    }
                    clue = readClue(connection, clueId).orElseThrow();
                }
                CombatIncident incident = readIncident(connection, incidentId).orElseThrow();
                connection.commit();
                return LawOperation.success(new DeathResult(incident, Optional.ofNullable(clue)));
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public LawOperation<PoliceReport> fileEmergencyReport(UUID reporter, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<CombatIncident> incident = latestReportableDeath(
                        connection, reporter, now - reportWindowMillis);
                if (incident.isEmpty()) return rollback(connection, LawResult.NO_REPORTABLE_DEATH);
                if (reportForIncident(connection, incident.get().id()).isPresent()) {
                    return rollback(connection, LawResult.ALREADY_REPORTED);
                }
                long reportId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO police_reports(
                            incident_id, reporter_uuid, suspect_uuid, filed_at, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, incident.get().id());
                    statement.setString(2, reporter.toString());
                    statement.setString(3, incident.get().attackerId().toString());
                    statement.setLong(4, now);
                    statement.setLong(5, now);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Report insert returned no id");
                        reportId = keys.getLong(1);
                    }
                }
                reportEvent(connection, reportId, reporter, "REPORT_FILED", null, now);
                PoliceReport report = readReport(connection, reportId).orElseThrow();
                connection.commit();
                return LawOperation.success(report);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public LawOperation<List<PoliceReport>> reports(
            UUID officer, int limit, int offset, ReportStatus status) throws SQLException {
        String where = status == null ? "" : " WHERE r.status = ?";
        String sql = reportSelect() + where + " ORDER BY r.filed_at DESC, r.id DESC LIMIT ? OFFSET ?";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (!lawEnforcement(connection, officer)) {
                return LawOperation.failure(LawResult.NOT_AUTHORIZED);
            }
            int index = 1;
            if (status != null) statement.setString(index++, status.name());
            statement.setInt(index++, limit);
            statement.setInt(index, offset);
            try (ResultSet results = statement.executeQuery()) {
                List<PoliceReport> reports = new ArrayList<>();
                while (results.next()) reports.add(readReport(results));
                return LawOperation.success(List.copyOf(reports));
            }
        }
    }

    public LawOperation<PoliceReportDetails> reportDetails(long reportId, UUID viewer) throws SQLException {
        try (Connection connection = database.openConnection()) {
            Optional<PoliceReport> selected = readReport(connection, reportId);
            if (selected.isEmpty()) return LawOperation.failure(LawResult.NOT_FOUND);
            if (!viewer.equals(selected.get().reporterId())
                    && !viewer.equals(selected.get().suspectId())
                    && !lawEnforcement(connection, viewer)) {
                return LawOperation.failure(LawResult.NOT_AUTHORIZED);
            }
            long incidentId = selected.get().incidentId();
            return LawOperation.success(new PoliceReportDetails(
                    selected.get(), readIncident(connection, incidentId).orElseThrow(),
                    clueForIncident(connection, incidentId), chargeForReport(connection, reportId),
                    reportEvents(connection, reportId)));
        }
    }

    public boolean isLawEnforcement(UUID player) throws SQLException {
        try (Connection connection = database.openConnection()) {
            return lawEnforcement(connection, player);
        }
    }

    public List<UUID> lawEnforcementIds() throws SQLException {
        String placeholders = String.join(",", LAW_ENFORCEMENT_JOBS.stream().map(ignored -> "?").toList());
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT DISTINCT player_uuid FROM citizen_jobs WHERE job_id IN ("
                             + placeholders + ")")) {
            for (int i = 0; i < LAW_ENFORCEMENT_JOBS.size(); i++) {
                statement.setString(i + 1, LAW_ENFORCEMENT_JOBS.get(i));
            }
            try (ResultSet results = statement.executeQuery()) {
                List<UUID> officers = new ArrayList<>();
                while (results.next()) officers.add(UUID.fromString(results.getString(1)));
                return List.copyOf(officers);
            }
        }
    }

    public LawOperation<PoliceReport> claimReport(long reportId, UUID officer, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!lawEnforcement(connection, officer)) {
                    return rollback(connection, LawResult.NOT_AUTHORIZED);
                }
                Optional<PoliceReport> selected = readReport(connection, reportId);
                if (selected.isEmpty()) return rollback(connection, LawResult.NOT_FOUND);
                PoliceReport report = selected.get();
                if (report.status() != ReportStatus.OPEN) {
                    if (report.status() == ReportStatus.CLAIMED
                            && officer.equals(report.assignedOfficerId())) {
                        return rollback(connection, LawResult.ALREADY_ASSIGNED);
                    }
                    return rollback(connection, report.status() == ReportStatus.CLAIMED
                            ? LawResult.ASSIGNED_TO_OTHER : LawResult.INVALID_STATE);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE police_reports SET status = 'CLAIMED', assigned_officer_uuid = ?, updated_at = ?
                        WHERE id = ? AND status = 'OPEN'
                        """)) {
                    statement.setString(1, officer.toString());
                    statement.setLong(2, now);
                    statement.setLong(3, reportId);
                    statement.executeUpdate();
                }
                reportEvent(connection, reportId, officer, "REPORT_CLAIMED", null, now);
                PoliceReport updated = readReport(connection, reportId).orElseThrow();
                connection.commit();
                return LawOperation.success(updated);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public LawOperation<PoliceReport> dismissReport(
            long reportId, UUID officer, String resolution, long now) throws SQLException {
        if (resolution.isBlank() || resolution.length() > 1_000) {
            return LawOperation.failure(LawResult.INVALID_CONTENT);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!lawEnforcement(connection, officer)) {
                    return rollback(connection, LawResult.NOT_AUTHORIZED);
                }
                Optional<PoliceReport> selected = readReport(connection, reportId);
                if (selected.isEmpty()) return rollback(connection, LawResult.NOT_FOUND);
                LawResult access = reportAccess(connection, selected.get(), officer);
                if (access != LawResult.SUCCESS) return rollback(connection, access);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE police_reports SET status = 'DISMISSED', assigned_officer_uuid = ?,
                            updated_at = ?, resolution = ? WHERE id = ?
                        """)) {
                    statement.setString(1, officer.toString());
                    statement.setLong(2, now);
                    statement.setString(3, resolution);
                    statement.setLong(4, reportId);
                    statement.executeUpdate();
                }
                reportEvent(connection, reportId, officer, "REPORT_DISMISSED", resolution, now);
                PoliceReport updated = readReport(connection, reportId).orElseThrow();
                connection.commit();
                return LawOperation.success(updated);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public LawOperation<PoliceCharge> chargeReport(
            long reportId, UUID officer, Offense offense, String reason, long now) throws SQLException {
        if (!validReason(reason)) return LawOperation.failure(LawResult.INVALID_CONTENT);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!lawEnforcement(connection, officer)) {
                    return rollback(connection, LawResult.NOT_AUTHORIZED);
                }
                Optional<PoliceReport> selected = readReport(connection, reportId);
                if (selected.isEmpty()) return rollback(connection, LawResult.NOT_FOUND);
                LawResult access = reportAccess(connection, selected.get(), officer);
                if (access != LawResult.SUCCESS) return rollback(connection, access);
                long chargeId = insertCharge(connection, reportId, selected.get().suspectId(),
                        officer, offense, reason, now);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE police_reports SET status = 'CHARGED', assigned_officer_uuid = ?,
                            updated_at = ?, resolution = ? WHERE id = ?
                        """)) {
                    statement.setString(1, officer.toString());
                    statement.setLong(2, now);
                    statement.setString(3, "Charge #" + chargeId);
                    statement.setLong(4, reportId);
                    statement.executeUpdate();
                }
                reportEvent(connection, reportId, officer, "CHARGE_FILED",
                        offense.id() + " #" + chargeId, now);
                PoliceCharge charge = readCharge(connection, chargeId).orElseThrow();
                connection.commit();
                return LawOperation.success(charge);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public LawOperation<PoliceCharge> chargeCitizen(
            UUID suspect, UUID officer, Offense offense, String reason, long now) throws SQLException {
        if (!validReason(reason)) return LawOperation.failure(LawResult.INVALID_CONTENT);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!lawEnforcement(connection, officer)) {
                    return rollback(connection, LawResult.NOT_AUTHORIZED);
                }
                if (!playerExists(connection, suspect)) {
                    return rollback(connection, LawResult.PLAYER_NOT_FOUND);
                }
                long chargeId = insertCharge(connection, null, suspect, officer, offense, reason, now);
                PoliceCharge charge = readCharge(connection, chargeId).orElseThrow();
                connection.commit();
                return LawOperation.success(charge);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public LawOperation<PoliceCharge> voidCharge(
            long chargeId, UUID officer, String resolution, long now) throws SQLException {
        if (!validReason(resolution)) return LawOperation.failure(LawResult.INVALID_CONTENT);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!lawEnforcement(connection, officer)) {
                    return rollback(connection, LawResult.NOT_AUTHORIZED);
                }
                Optional<PoliceCharge> selected = readCharge(connection, chargeId);
                if (selected.isEmpty()) return rollback(connection, LawResult.NOT_FOUND);
                if (selected.get().status() == ChargeStatus.VOIDED) {
                    return rollback(connection, LawResult.INVALID_STATE);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE police_charges SET status = 'VOIDED', resolved_at = ?, resolution = ?
                        WHERE id = ?
                        """)) {
                    statement.setLong(1, now);
                    statement.setString(2, resolution);
                    statement.setLong(3, chargeId);
                    statement.executeUpdate();
                }
                if (selected.get().reportId() != null) reportEvent(
                        connection, selected.get().reportId(), officer, "CHARGE_VOIDED", resolution, now);
                if (selected.get().reportId() != null) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE police_reports SET status = 'DISMISSED', updated_at = ?, resolution = ?
                            WHERE id = ?
                            """)) {
                        statement.setLong(1, now);
                        statement.setString(2, resolution);
                        statement.setLong(3, selected.get().reportId());
                        statement.executeUpdate();
                    }
                }
                PoliceCharge updated = readCharge(connection, chargeId).orElseThrow();
                connection.commit();
                return LawOperation.success(updated);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<PoliceCharge> wanted(UUID suspect) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     chargeSelect() + " WHERE c.suspect_uuid = ? AND c.status = 'OPEN'"
                             + " ORDER BY c.charged_at, c.id")) {
            statement.setString(1, suspect.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<PoliceCharge> charges = new ArrayList<>();
                while (results.next()) charges.add(readCharge(results));
                return List.copyOf(charges);
            }
        }
    }

    public List<WantedCitizen> wantedCitizens(int limit, int offset) throws SQLException {
        String sql = """
                SELECT p.uuid, p.last_name, COUNT(*) AS charge_count,
                    SUM(c.fine_cents) AS total_fine, SUM(c.jail_minutes) AS total_jail,
                    MIN(c.charged_at) AS oldest_charge
                FROM police_charges c JOIN players p ON p.uuid = c.suspect_uuid
                WHERE c.status = 'OPEN'
                GROUP BY p.uuid, p.last_name
                ORDER BY oldest_charge, p.last_name COLLATE NOCASE
                LIMIT ? OFFSET ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            statement.setInt(2, offset);
            try (ResultSet results = statement.executeQuery()) {
                List<WantedCitizen> wanted = new ArrayList<>();
                while (results.next()) wanted.add(new WantedCitizen(
                        UUID.fromString(results.getString("uuid")), results.getString("last_name"),
                        results.getInt("charge_count"), results.getLong("total_fine"),
                        results.getInt("total_jail"),
                        Instant.ofEpochMilli(results.getLong("oldest_charge"))));
                return List.copyOf(wanted);
            }
        }
    }

    public LawOperation<PoliceArrest> arrest(
            UUID officer,
            UUID suspect,
            int warrantHoldMinutes,
            int maximumJailMinutes,
            long now
    ) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!lawEnforcement(connection, officer)) {
                    return rollback(connection, LawResult.NOT_AUTHORIZED);
                }
                if (!playerExists(connection, suspect)) {
                    return rollback(connection, LawResult.PLAYER_NOT_FOUND);
                }
                expireWarrants(connection, now);
                if (hasActiveDetention(connection, suspect)) {
                    return rollback(connection, LawResult.ALREADY_DETAINED);
                }
                List<PoliceCharge> charges = openCharges(connection, suspect);
                List<Long> warrants = activeArrestWarrantIds(connection, suspect, now);
                if (charges.isEmpty() && warrants.isEmpty()) {
                    return rollback(connection, LawResult.NOT_WANTED);
                }

                long fine = 0;
                int jail = 0;
                List<Long> chargeIds = new ArrayList<>();
                List<String> reasons = new ArrayList<>();
                for (PoliceCharge charge : charges) {
                    long addition = Math.min(charge.fineCents(), MAX_FINE_CENTS);
                    fine = fine >= MAX_FINE_CENTS - addition ? MAX_FINE_CENTS : fine + addition;
                    jail = Math.min(maximumJailMinutes, jail + charge.jailMinutes());
                    chargeIds.add(charge.id());
                    reasons.add(charge.offenseId() + " #" + charge.id());
                }
                if (!warrants.isEmpty()) {
                    jail = Math.max(jail, warrantHoldMinutes);
                    reasons.add(warrants.size() == 1
                            ? "court arrest warrant" : warrants.size() + " court arrest warrants");
                }
                long collected = collectFine(connection, suspect, fine, now);
                long releaseAt = Math.addExact(now, Duration.ofMinutes(jail).toMillis());
                ArrestStatus status = jail == 0 ? ArrestStatus.RELEASED : ArrestStatus.ACTIVE;
                long arrestId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO police_arrests(
                            suspect_uuid, officer_uuid, reason, fine_assessed_cents,
                            fine_collected_cents, jail_minutes, arrested_at, release_at,
                            released_at, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, suspect.toString());
                    statement.setString(2, officer.toString());
                    statement.setString(3, String.join(", ", reasons));
                    statement.setLong(4, fine);
                    statement.setLong(5, collected);
                    statement.setInt(6, jail);
                    statement.setLong(7, now);
                    statement.setLong(8, releaseAt);
                    if (status == ArrestStatus.RELEASED) statement.setLong(9, now);
                    else statement.setNull(9, java.sql.Types.BIGINT);
                    statement.setString(10, status.name());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Arrest insert returned no id");
                        arrestId = keys.getLong(1);
                    }
                }
                for (PoliceCharge charge : charges) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE police_charges SET status = 'SERVED', resolved_at = ?,
                                resolution = ? WHERE id = ? AND status = 'OPEN'
                            """)) {
                        statement.setLong(1, now);
                        statement.setString(2, "Arrest #" + arrestId);
                        statement.setLong(3, charge.id());
                        statement.executeUpdate();
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO police_arrest_charges(arrest_id, charge_id) VALUES (?, ?)
                            """)) {
                        statement.setLong(1, arrestId);
                        statement.setLong(2, charge.id());
                        statement.executeUpdate();
                    }
                    if (charge.reportId() != null) reportEvent(connection, charge.reportId(), officer,
                            "SUSPECT_ARRESTED", "Arrest #" + arrestId, now);
                }
                for (long warrantId : warrants) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO police_arrest_warrants(arrest_id, warrant_id) VALUES (?, ?)
                            """)) {
                        statement.setLong(1, arrestId);
                        statement.setLong(2, warrantId);
                        statement.executeUpdate();
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE court_warrants SET status = 'EXECUTED'
                        WHERE target_uuid = ? AND warrant_type = 'ARREST'
                            AND status = 'ACTIVE' AND expires_at > ?
                        """)) {
                    statement.setString(1, suspect.toString());
                    statement.setLong(2, now);
                    statement.executeUpdate();
                }
                PoliceArrest arrest = readArrest(connection, arrestId).orElseThrow();
                connection.commit();
                return LawOperation.success(arrest);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<PoliceArrest> activeDetentions() throws SQLException {
        try (Connection connection = database.openConnection()) {
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM police_arrests WHERE status = 'ACTIVE' ORDER BY release_at, id");
                 ResultSet results = statement.executeQuery()) {
                while (results.next()) ids.add(results.getLong(1));
            }
            List<PoliceArrest> arrests = new ArrayList<>();
            for (long id : ids) arrests.add(readArrest(connection, id).orElseThrow());
            return List.copyOf(arrests);
        }
    }

    public List<PoliceArrest> releaseDue(long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                List<Long> ids = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id FROM police_arrests
                        WHERE status = 'ACTIVE' AND release_at <= ? ORDER BY release_at, id
                        """)) {
                    statement.setLong(1, now);
                    try (ResultSet results = statement.executeQuery()) {
                        while (results.next()) ids.add(results.getLong(1));
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE police_arrests SET status = 'RELEASED', released_at = ?
                        WHERE status = 'ACTIVE' AND release_at <= ?
                        """)) {
                    statement.setLong(1, now);
                    statement.setLong(2, now);
                    statement.executeUpdate();
                }
                List<PoliceArrest> released = new ArrayList<>();
                for (long id : ids) released.add(readArrest(connection, id).orElseThrow());
                connection.commit();
                return List.copyOf(released);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public LawOperation<ForensicClue> collectClue(long clueId, UUID officer, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!lawEnforcement(connection, officer)) {
                    return rollback(connection, LawResult.NOT_AUTHORIZED);
                }
                Optional<ForensicClue> selected = readClue(connection, clueId);
                if (selected.isEmpty()) return rollback(connection, LawResult.NOT_FOUND);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE forensic_clues SET status = 'COLLECTED', collector_uuid = ?, collected_at = ?
                        WHERE id = ? AND status = 'AVAILABLE'
                        """)) {
                    statement.setString(1, officer.toString());
                    statement.setLong(2, now);
                    statement.setLong(3, clueId);
                    if (statement.executeUpdate() == 0) {
                        return rollback(connection, LawResult.CLUE_UNAVAILABLE);
                    }
                }
                ForensicClue clue = readClue(connection, clueId).orElseThrow();
                connection.commit();
                return LawOperation.success(clue);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public LawOperation<ForensicClue> clue(long clueId, UUID officer) throws SQLException {
        try (Connection connection = database.openConnection()) {
            if (!lawEnforcement(connection, officer)) {
                return LawOperation.failure(LawResult.NOT_AUTHORIZED);
            }
            return readClue(connection, clueId).map(LawOperation::success)
                    .orElseGet(() -> LawOperation.failure(LawResult.NOT_FOUND));
        }
    }

    public List<PublicCriminalRecord> criminalRecords(UUID player) throws SQLException {
        String sql = """
                SELECT reference, charge, fine_cents, jail_minutes, recorded_at, cleared FROM (
                    SELECT c.case_number AS reference, r.charge, r.fine_cents, r.jail_minutes,
                        r.convicted_at AS recorded_at,
                        CASE WHEN r.pardoned_at IS NULL THEN 0 ELSE 1 END AS cleared
                    FROM criminal_records r JOIN court_cases c ON c.id = r.case_id
                    WHERE r.defendant_uuid = ?
                    UNION ALL
                    SELECT 'PC-' || c.id, c.offense_id, c.fine_cents, c.jail_minutes,
                        c.charged_at, CASE WHEN c.status = 'VOIDED' THEN 1 ELSE 0 END
                    FROM police_charges c
                    WHERE c.suspect_uuid = ? AND (c.status = 'SERVED'
                        OR (c.status = 'VOIDED' AND EXISTS (
                            SELECT 1 FROM police_arrest_charges ac WHERE ac.charge_id = c.id)))
                ) ORDER BY recorded_at DESC, reference DESC
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            statement.setString(2, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<PublicCriminalRecord> records = new ArrayList<>();
                while (results.next()) records.add(new PublicCriminalRecord(
                        results.getString("reference"), results.getString("charge"),
                        results.getLong("fine_cents"), results.getInt("jail_minutes"),
                        Instant.ofEpochMilli(results.getLong("recorded_at")),
                        results.getInt("cleared") != 0));
                return List.copyOf(records);
            }
        }
    }

    private static LegalBasis legalBasis(
            Connection connection, UUID attacker, UUID victim, long cutoff) throws SQLException {
        if (consent(connection, victim)) return LegalBasis.CONSENT;
        if (recentUnlawful(connection, victim, attacker, cutoff)) return LegalBasis.SELF_DEFENSE;
        return LegalBasis.UNLAWFUL;
    }

    private static boolean consent(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT consent_enabled FROM pvp_preferences WHERE player_uuid = ?
                """)) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() && results.getInt(1) != 0;
            }
        }
    }

    private static boolean recentUnlawful(
            Connection connection, UUID attacker, UUID victim, long cutoff) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM combat_incidents
                WHERE attacker_uuid = ? AND victim_uuid = ?
                    AND legal_basis = 'UNLAWFUL' AND attacked_at >= ?
                LIMIT 1
                """)) {
            statement.setString(1, attacker.toString());
            statement.setString(2, victim.toString());
            statement.setLong(3, cutoff);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static long insertIncident(
            Connection connection,
            UUID attacker,
            UUID victim,
            String world,
            double x,
            double y,
            double z,
            int damage,
            String cause,
            byte[] weapon,
            LegalBasis basis,
            long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO combat_incidents(
                    attacker_uuid, victim_uuid, world_name, x, y, z, damage_millihearts,
                    damage_cause, weapon_data, legal_basis, attacked_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, attacker.toString());
            statement.setString(2, victim.toString());
            statement.setString(3, world);
            statement.setDouble(4, x);
            statement.setDouble(5, y);
            statement.setDouble(6, z);
            statement.setInt(7, damage);
            statement.setString(8, cause);
            if (weapon == null) statement.setNull(9, java.sql.Types.BLOB);
            else statement.setBytes(9, weapon);
            statement.setString(10, basis.name());
            statement.setLong(11, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Combat incident insert returned no id");
                return keys.getLong(1);
            }
        }
    }

    private static Optional<CombatIncident> latestIncident(
            Connection connection, UUID attacker, UUID victim, long cutoff) throws SQLException {
        String sql = incidentSelect() + " WHERE i.attacker_uuid = ? AND i.victim_uuid = ?"
                + " AND i.attacked_at >= ? AND i.fatal = 0 ORDER BY i.attacked_at DESC, i.id DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, attacker.toString());
            statement.setString(2, victim.toString());
            statement.setLong(3, cutoff);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readIncident(results)) : Optional.empty();
            }
        }
    }

    private static Optional<CombatIncident> latestReportableDeath(
            Connection connection, UUID victim, long cutoff) throws SQLException {
        String sql = incidentSelect() + " WHERE i.victim_uuid = ? AND i.fatal = 1"
                + " AND i.legal_basis = 'UNLAWFUL' AND i.death_at >= ?"
                + " ORDER BY i.death_at DESC, i.id DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, victim.toString());
            statement.setLong(2, cutoff);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readIncident(results)) : Optional.empty();
            }
        }
    }

    private static Optional<CombatIncident> readIncident(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                incidentSelect() + " WHERE i.id = ?")) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readIncident(results)) : Optional.empty();
            }
        }
    }

    private static CombatIncident readIncident(ResultSet results) throws SQLException {
        long death = results.getLong("death_at");
        boolean hasDeath = !results.wasNull();
        return new CombatIncident(
                results.getLong("id"), UUID.fromString(results.getString("attacker_uuid")),
                results.getString("attacker_name"), UUID.fromString(results.getString("victim_uuid")),
                results.getString("victim_name"), results.getString("world_name"),
                results.getDouble("x"), results.getDouble("y"), results.getDouble("z"),
                results.getInt("damage_millihearts"), results.getString("damage_cause"),
                results.getBytes("weapon_data"), LegalBasis.valueOf(results.getString("legal_basis")),
                Instant.ofEpochMilli(results.getLong("attacked_at")), results.getInt("fatal") != 0,
                hasDeath ? Instant.ofEpochMilli(death) : null);
    }

    private static String incidentSelect() {
        return """
                SELECT i.*, a.last_name AS attacker_name, v.last_name AS victim_name
                FROM combat_incidents i
                JOIN players a ON a.uuid = i.attacker_uuid
                JOIN players v ON v.uuid = i.victim_uuid
                """;
    }

    private static Optional<ForensicClue> clueForIncident(
            Connection connection, long incidentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM forensic_clues WHERE incident_id = ?")) {
            statement.setLong(1, incidentId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? readClue(connection, results.getLong(1)) : Optional.empty();
            }
        }
    }

    private static Optional<ForensicClue> readClue(Connection connection, long clueId) throws SQLException {
        long incidentId;
        String status;
        String collector;
        long created;
        Long collected;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT f.*, p.last_name AS collector_name FROM forensic_clues f
                LEFT JOIN players p ON p.uuid = f.collector_uuid WHERE f.id = ?
                """)) {
            statement.setLong(1, clueId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) return Optional.empty();
                incidentId = results.getLong("incident_id");
                status = results.getString("status");
                collector = results.getString("collector_name");
                created = results.getLong("created_at");
                long value = results.getLong("collected_at");
                collected = results.wasNull() ? null : value;
            }
        }
        return Optional.of(new ForensicClue(
                clueId, readIncident(connection, incidentId).orElseThrow(), status, collector,
                Instant.ofEpochMilli(created), collected == null ? null : Instant.ofEpochMilli(collected)));
    }

    private static Optional<PoliceReport> reportForIncident(
            Connection connection, long incidentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                reportSelect() + " WHERE r.incident_id = ?")) {
            statement.setLong(1, incidentId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readReport(results)) : Optional.empty();
            }
        }
    }

    private static Optional<PoliceReport> readReport(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                reportSelect() + " WHERE r.id = ?")) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readReport(results)) : Optional.empty();
            }
        }
    }

    private static PoliceReport readReport(ResultSet results) throws SQLException {
        return new PoliceReport(
                results.getLong("id"), results.getLong("incident_id"),
                UUID.fromString(results.getString("reporter_uuid")), results.getString("reporter_name"),
                UUID.fromString(results.getString("suspect_uuid")), results.getString("suspect_name"),
                ReportStatus.valueOf(results.getString("status")),
                results.getString("assigned_officer_uuid") == null ? null
                        : UUID.fromString(results.getString("assigned_officer_uuid")),
                results.getString("officer_name"), Instant.ofEpochMilli(results.getLong("filed_at")),
                Instant.ofEpochMilli(results.getLong("updated_at")), results.getString("resolution"));
    }

    private static String reportSelect() {
        return """
                SELECT r.*, reporter.last_name AS reporter_name,
                    suspect.last_name AS suspect_name, officer.last_name AS officer_name
                FROM police_reports r
                JOIN players reporter ON reporter.uuid = r.reporter_uuid
                JOIN players suspect ON suspect.uuid = r.suspect_uuid
                LEFT JOIN players officer ON officer.uuid = r.assigned_officer_uuid
                """;
    }

    private static List<PoliceReportEvent> reportEvents(
            Connection connection, long reportId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.*, p.last_name AS actor_name FROM police_report_events e
                LEFT JOIN players p ON p.uuid = e.actor_uuid
                WHERE e.report_id = ? ORDER BY e.created_at, e.id
                """)) {
            statement.setLong(1, reportId);
            try (ResultSet results = statement.executeQuery()) {
                List<PoliceReportEvent> events = new ArrayList<>();
                while (results.next()) events.add(new PoliceReportEvent(
                        results.getLong("id"), results.getString("actor_name"),
                        results.getString("event_type"), results.getString("event_text"),
                        Instant.ofEpochMilli(results.getLong("created_at"))));
                return List.copyOf(events);
            }
        }
    }

    private static void reportEvent(
            Connection connection, long reportId, UUID actor, String type, String text, long now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO police_report_events(report_id, actor_uuid, event_type, event_text, created_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, reportId);
            if (actor == null) statement.setNull(2, java.sql.Types.VARCHAR);
            else statement.setString(2, actor.toString());
            statement.setString(3, type);
            if (text == null) statement.setNull(4, java.sql.Types.VARCHAR);
            else statement.setString(4, text);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private static LawResult reportAccess(
            Connection connection, PoliceReport report, UUID officer) throws SQLException {
        if (report.status() != ReportStatus.OPEN && report.status() != ReportStatus.CLAIMED) {
            return LawResult.INVALID_STATE;
        }
        if (report.status() == ReportStatus.CLAIMED
                && !officer.equals(report.assignedOfficerId())) {
            return LawResult.ASSIGNED_TO_OTHER;
        }
        return LawResult.SUCCESS;
    }

    private static long insertCharge(
            Connection connection,
            Long reportId,
            UUID suspect,
            UUID officer,
            Offense offense,
            String reason,
            long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO police_charges(
                    report_id, suspect_uuid, officer_uuid, offense_id, reason,
                    fine_cents, jail_minutes, charged_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            if (reportId == null) statement.setNull(1, java.sql.Types.BIGINT);
            else statement.setLong(1, reportId);
            statement.setString(2, suspect.toString());
            statement.setString(3, officer.toString());
            statement.setString(4, offense.id());
            statement.setString(5, reason);
            statement.setLong(6, offense.fineCents());
            statement.setInt(7, offense.jailMinutes());
            statement.setLong(8, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Charge insert returned no id");
                return keys.getLong(1);
            }
        }
    }

    private static Optional<PoliceCharge> chargeForReport(
            Connection connection, long reportId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                chargeSelect() + " WHERE c.report_id = ?")) {
            statement.setLong(1, reportId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readCharge(results)) : Optional.empty();
            }
        }
    }

    private static Optional<PoliceCharge> readCharge(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                chargeSelect() + " WHERE c.id = ?")) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readCharge(results)) : Optional.empty();
            }
        }
    }

    private static PoliceCharge readCharge(ResultSet results) throws SQLException {
        long report = results.getLong("report_id");
        boolean hasReport = !results.wasNull();
        long resolved = results.getLong("resolved_at");
        boolean hasResolved = !results.wasNull();
        return new PoliceCharge(
                results.getLong("id"), hasReport ? report : null,
                results.getString("suspect_name"), results.getString("officer_name"),
                results.getString("offense_id"), results.getString("reason"),
                results.getLong("fine_cents"), results.getInt("jail_minutes"),
                ChargeStatus.valueOf(results.getString("status")),
                Instant.ofEpochMilli(results.getLong("charged_at")),
                hasResolved ? Instant.ofEpochMilli(resolved) : null, results.getString("resolution"));
    }

    private static String chargeSelect() {
        return """
                SELECT c.*, suspect.last_name AS suspect_name, officer.last_name AS officer_name
                FROM police_charges c
                JOIN players suspect ON suspect.uuid = c.suspect_uuid
                JOIN players officer ON officer.uuid = c.officer_uuid
                """;
    }

    private static List<PoliceCharge> openCharges(Connection connection, UUID suspect) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                chargeSelect() + " WHERE c.suspect_uuid = ? AND c.status = 'OPEN'"
                        + " ORDER BY c.charged_at, c.id")) {
            statement.setString(1, suspect.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<PoliceCharge> charges = new ArrayList<>();
                while (results.next()) charges.add(readCharge(results));
                return charges;
            }
        }
    }

    private static Optional<PoliceArrest> readArrest(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                arrestSelect() + " WHERE a.id = ?")) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readArrest(connection, results)) : Optional.empty();
            }
        }
    }

    private static PoliceArrest readArrest(Connection connection, ResultSet results) throws SQLException {
        long id = results.getLong("id");
        long released = results.getLong("released_at");
        boolean hasReleased = !results.wasNull();
        List<Long> charges = new ArrayList<>();
        List<String> offenses = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ac.charge_id, c.offense_id FROM police_arrest_charges ac
                JOIN police_charges c ON c.id = ac.charge_id
                WHERE ac.arrest_id = ? ORDER BY ac.charge_id
                """)) {
            statement.setLong(1, id);
            try (ResultSet linked = statement.executeQuery()) {
                while (linked.next()) {
                    charges.add(linked.getLong("charge_id"));
                    offenses.add(linked.getString("offense_id"));
                }
            }
        }
        List<Long> warrants = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT warrant_id FROM police_arrest_warrants WHERE arrest_id = ? ORDER BY warrant_id
                """)) {
            statement.setLong(1, id);
            try (ResultSet linked = statement.executeQuery()) {
                while (linked.next()) warrants.add(linked.getLong(1));
            }
        }
        return new PoliceArrest(
                id, UUID.fromString(results.getString("suspect_uuid")),
                results.getString("suspect_name"), results.getString("officer_name"),
                results.getString("reason"), results.getLong("fine_assessed_cents"),
                results.getLong("fine_collected_cents"), results.getInt("jail_minutes"),
                Instant.ofEpochMilli(results.getLong("arrested_at")),
                Instant.ofEpochMilli(results.getLong("release_at")),
                hasReleased ? Instant.ofEpochMilli(released) : null,
                ArrestStatus.valueOf(results.getString("status")), charges, offenses, warrants);
    }

    private static String arrestSelect() {
        return """
                SELECT a.*, suspect.last_name AS suspect_name, officer.last_name AS officer_name
                FROM police_arrests a
                JOIN players suspect ON suspect.uuid = a.suspect_uuid
                JOIN players officer ON officer.uuid = a.officer_uuid
                """;
    }

    private static boolean hasActiveDetention(Connection connection, UUID suspect) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM police_arrests WHERE suspect_uuid = ? AND status = 'ACTIVE' LIMIT 1
                """)) {
            statement.setString(1, suspect.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static List<Long> activeArrestWarrantIds(
            Connection connection, UUID suspect, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM court_warrants
                WHERE target_uuid = ? AND warrant_type = 'ARREST'
                    AND status = 'ACTIVE' AND expires_at > ?
                ORDER BY issued_at, id
                """)) {
            statement.setString(1, suspect.toString());
            statement.setLong(2, now);
            try (ResultSet results = statement.executeQuery()) {
                List<Long> warrants = new ArrayList<>();
                while (results.next()) warrants.add(results.getLong(1));
                return List.copyOf(warrants);
            }
        }
    }

    private static long collectFine(
            Connection connection, UUID suspect, long assessed, long now) throws SQLException {
        if (assessed == 0) return 0;
        long balance;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_cents FROM accounts WHERE player_uuid = ?")) {
            statement.setString(1, suspect.toString());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) throw new SQLException("Suspect account was not found");
                balance = results.getLong(1);
            }
        }
        long collected = Math.min(balance, assessed);
        if (collected == 0) return 0;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE accounts SET balance_cents = balance_cents - ? WHERE player_uuid = ?
                """)) {
            statement.setLong(1, collected);
            statement.setString(2, suspect.toString());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger_entries(player_uuid, amount_cents, entry_type, created_at)
                VALUES (?, ?, 'POLICE_FINE', ?)
                """)) {
            statement.setString(1, suspect.toString());
            statement.setLong(2, -collected);
            statement.setLong(3, now);
            statement.executeUpdate();
        }
        return collected;
    }

    private static void expireWarrants(Connection connection, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE court_warrants SET status = 'EXPIRED'
                WHERE status = 'ACTIVE' AND expires_at <= ?
                """)) {
            statement.setLong(1, now);
            statement.executeUpdate();
        }
    }

    private static boolean lawEnforcement(Connection connection, UUID player) throws SQLException {
        String placeholders = String.join(",", LAW_ENFORCEMENT_JOBS.stream().map(ignored -> "?").toList());
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM citizen_jobs WHERE player_uuid = ? AND job_id IN ("
                        + placeholders + ") LIMIT 1")) {
            statement.setString(1, player.toString());
            for (int i = 0; i < LAW_ENFORCEMENT_JOBS.size(); i++) {
                statement.setString(i + 2, LAW_ENFORCEMENT_JOBS.get(i));
            }
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static boolean playerExists(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM players WHERE uuid = ? LIMIT 1")) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static boolean validReason(String reason) {
        return reason != null && !reason.isBlank() && reason.length() <= 1_000;
    }

    private static <T> LawOperation<T> rollback(Connection connection, LawResult result) throws SQLException {
        connection.rollback();
        return LawOperation.failure(result);
    }
}
