package dev.opencivitas.court;

import dev.opencivitas.database.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class CourtRepository {
    private static final long DISTRICT_CIVIL_LIMIT_CENTS = 12_000_000L;

    private final Database database;

    public CourtRepository(Database database) {
        this.database = database;
    }

    public CourtOperation fileCivil(
            UUID filer,
            UUID defendant,
            String title,
            String claim,
            long amountCents,
            long now
    ) throws SQLException {
        if (amountCents <= 0 || amountCents > 1_000_000_000_00L) {
            return CourtOperation.result(CourtActionResult.INVALID_CONTENT);
        }
        CourtLevel level = amountCents <= DISTRICT_CIVIL_LIMIT_CENTS
                ? CourtLevel.DISTRICT : CourtLevel.FEDERAL;
        return file(filer, filer, defendant, level, CourtCaseType.CIVIL,
                title, claim, amountCents, null, now);
    }

    public CourtOperation fileCriminal(
            UUID prosecutor,
            UUID defendant,
            boolean major,
            String title,
            String charge,
            long now
    ) throws SQLException {
        try (Connection connection = database.openConnection()) {
            if (!hasJob(connection, prosecutor, "prosecutor")) {
                return CourtOperation.result(CourtActionResult.NOT_AUTHORIZED);
            }
        }
        return file(prosecutor, prosecutor, defendant,
                major ? CourtLevel.FEDERAL : CourtLevel.DISTRICT,
                CourtCaseType.CRIMINAL, title, charge, 0, null, now);
    }

    public CourtOperation fileConstitutional(
            UUID filer,
            UUID respondent,
            String title,
            String claim,
            long now
    ) throws SQLException {
        return file(filer, filer, respondent, CourtLevel.FEDERAL,
                CourtCaseType.CONSTITUTIONAL, title, claim, 0, null, now);
    }

    public CourtOperation fileInstitutional(
            UUID filer,
            UUID respondent,
            String title,
            String claim,
            long now
    ) throws SQLException {
        return file(filer, filer, respondent, CourtLevel.SUPREME,
                CourtCaseType.INSTITUTIONAL, title, claim, 0, null, now);
    }

    public List<CourtCase> list(int limit, int offset) throws SQLException {
        String sql = baseCaseSelect() + " ORDER BY c.filed_at DESC, c.id DESC LIMIT ? OFFSET ?";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            statement.setInt(2, offset);
            try (ResultSet results = statement.executeQuery()) {
                List<CourtCase> cases = new ArrayList<>();
                while (results.next()) cases.add(readCase(results));
                return List.copyOf(cases);
            }
        }
    }

    public Optional<CourtCaseDetails> details(long caseId) throws SQLException {
        try (Connection connection = database.openConnection()) {
            Optional<CourtCase> selected = readCase(connection, caseId);
            if (selected.isEmpty()) return Optional.empty();
            return Optional.of(new CourtCaseDetails(
                    selected.get(), counsel(connection, caseId), judges(connection, caseId),
                    evidence(connection, caseId), docket(connection, caseId),
                    orders(connection, caseId), warrants(connection, caseId)));
        }
    }

    public CourtOperation appointCounsel(
            long caseId, UUID party, UUID counsel, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<CourtCase> selected = readCase(connection, caseId);
                if (selected.isEmpty()) return rollback(connection, CourtActionResult.NOT_FOUND);
                CourtCase courtCase = selected.get();
                if (terminal(courtCase.status())) {
                    return rollback(connection, CourtActionResult.INVALID_STATE);
                }
                if (!playerExists(connection, counsel)) {
                    return rollback(connection, CourtActionResult.PLAYER_NOT_FOUND);
                }
                String role;
                if (courtCase.plaintiffId().equals(party)) role = "PLAINTIFF_COUNSEL";
                else if (courtCase.defendantId().equals(party)) role = "DEFENSE_COUNSEL";
                else return rollback(connection, CourtActionResult.NOT_PARTY);
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO court_participants(
                            case_id, player_uuid, role, appointed_by, appointed_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT(case_id, role) DO UPDATE SET
                            player_uuid = excluded.player_uuid,
                            appointed_by = excluded.appointed_by,
                            appointed_at = excluded.appointed_at
                        """)) {
                    statement.setLong(1, caseId);
                    statement.setString(2, counsel.toString());
                    statement.setString(3, role);
                    statement.setString(4, party.toString());
                    statement.setLong(5, now);
                    statement.executeUpdate();
                }
                docket(connection, caseId, party, "COUNSEL_APPOINTED", role, now);
                connection.commit();
                return CourtOperation.courtCase(courtCase);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public CourtOperation claimBench(long caseId, UUID judge, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<CourtCase> selected = readCase(connection, caseId);
                if (selected.isEmpty()) return rollback(connection, CourtActionResult.NOT_FOUND);
                CourtCase courtCase = selected.get();
                if (terminal(courtCase.status())) {
                    return rollback(connection, CourtActionResult.INVALID_STATE);
                }
                String role = judicialRole(connection, judge, courtCase.level()).orElse(null);
                if (role == null) return rollback(connection, CourtActionResult.NOT_JUDGE);
                int capacity = courtCase.level() == CourtLevel.SUPREME ? 3 : 1;
                if (judgeAssigned(connection, caseId, judge)) {
                    return rollback(connection, CourtActionResult.ALREADY_ASSIGNED);
                }
                if (judgeCount(connection, caseId) >= capacity) {
                    return rollback(connection, CourtActionResult.PANEL_FULL);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO court_case_judges(case_id, judge_uuid, judicial_role, joined_at)
                        VALUES (?, ?, ?, ?)
                        """)) {
                    statement.setLong(1, caseId);
                    statement.setString(2, judge.toString());
                    statement.setString(3, role);
                    statement.setLong(4, now);
                    statement.executeUpdate();
                }
                if (courtCase.status() == CourtCaseStatus.FILED) {
                    setStatus(connection, caseId, CourtCaseStatus.ASSIGNED);
                }
                docket(connection, caseId, judge, "JUDGE_ASSIGNED", role, now);
                CourtCase updated = readCase(connection, caseId).orElseThrow();
                connection.commit();
                return CourtOperation.courtCase(updated);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public CourtOperation schedule(long caseId, UUID judge, long scheduledAt, long now) throws SQLException {
        if (scheduledAt <= now) return CourtOperation.result(CourtActionResult.INVALID_CONTENT);
        try (Connection connection = database.openConnection()) {
            Optional<CourtCase> selected = readCase(connection, caseId);
            if (selected.isEmpty()) return CourtOperation.result(CourtActionResult.NOT_FOUND);
            if (!judgeAssigned(connection, caseId, judge)) {
                return CourtOperation.result(CourtActionResult.NOT_JUDGE);
            }
            if (selected.get().status() != CourtCaseStatus.ASSIGNED
                    && selected.get().status() != CourtCaseStatus.SCHEDULED) {
                return CourtOperation.result(CourtActionResult.INVALID_STATE);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE court_cases SET status = 'SCHEDULED', scheduled_at = ? WHERE id = ?
                    """)) {
                statement.setLong(1, scheduledAt);
                statement.setLong(2, caseId);
                statement.executeUpdate();
            }
            docket(connection, caseId, judge, "HEARING_SCHEDULED", Long.toString(scheduledAt), now);
            return CourtOperation.courtCase(readCase(connection, caseId).orElseThrow());
        }
    }

    public CourtOperation openHearing(long caseId, UUID judge, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            Optional<CourtCase> selected = readCase(connection, caseId);
            if (selected.isEmpty()) return CourtOperation.result(CourtActionResult.NOT_FOUND);
            if (!judgeAssigned(connection, caseId, judge)) {
                return CourtOperation.result(CourtActionResult.NOT_JUDGE);
            }
            if (selected.get().status() != CourtCaseStatus.ASSIGNED
                    && selected.get().status() != CourtCaseStatus.SCHEDULED) {
                return CourtOperation.result(CourtActionResult.INVALID_STATE);
            }
            setStatus(connection, caseId, CourtCaseStatus.HEARING);
            docket(connection, caseId, judge, "HEARING_OPENED", null, now);
            return CourtOperation.courtCase(readCase(connection, caseId).orElseThrow());
        }
    }

    public CourtOperation submitEvidence(
            long caseId,
            UUID actor,
            String description,
            byte[] itemData,
            long now
    ) throws SQLException {
        if (description.isBlank() || description.length() > 500) {
            return CourtOperation.result(CourtActionResult.INVALID_CONTENT);
        }
        try (Connection connection = database.openConnection()) {
            Optional<CourtCase> selected = readCase(connection, caseId);
            if (selected.isEmpty()) return CourtOperation.result(CourtActionResult.NOT_FOUND);
            if (terminal(selected.get().status())) {
                return CourtOperation.result(CourtActionResult.INVALID_STATE);
            }
            if (!canParticipate(connection, selected.get(), actor)) {
                return CourtOperation.result(CourtActionResult.NOT_AUTHORIZED);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO court_evidence(case_id, submitted_by, description, item_data, submitted_at)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                statement.setLong(1, caseId);
                statement.setString(2, actor.toString());
                statement.setString(3, description);
                if (itemData == null) statement.setNull(4, java.sql.Types.BLOB);
                else statement.setBytes(4, itemData);
                statement.setLong(5, now);
                statement.executeUpdate();
            }
            docket(connection, caseId, actor, "EVIDENCE_SUBMITTED", description, now);
            return CourtOperation.courtCase(selected.get());
        }
    }

    public CourtOperation motion(long caseId, UUID actor, String text, long now) throws SQLException {
        if (text.isBlank() || text.length() > 1_000) {
            return CourtOperation.result(CourtActionResult.INVALID_CONTENT);
        }
        try (Connection connection = database.openConnection()) {
            Optional<CourtCase> selected = readCase(connection, caseId);
            if (selected.isEmpty()) return CourtOperation.result(CourtActionResult.NOT_FOUND);
            if (terminal(selected.get().status())) {
                return CourtOperation.result(CourtActionResult.INVALID_STATE);
            }
            if (!canParticipate(connection, selected.get(), actor)) {
                return CourtOperation.result(CourtActionResult.NOT_AUTHORIZED);
            }
            docket(connection, caseId, actor, "MOTION_FILED", text, now);
            return CourtOperation.courtCase(selected.get());
        }
    }

    public CourtOperation issueOrder(
            long caseId,
            UUID judge,
            UUID target,
            String type,
            String text,
            Long expiresAt,
            long now
    ) throws SQLException {
        if (type.isBlank() || type.length() > 40 || text.isBlank() || text.length() > 1_000
                || expiresAt != null && expiresAt <= now) {
            return CourtOperation.result(CourtActionResult.INVALID_CONTENT);
        }
        try (Connection connection = database.openConnection()) {
            Optional<CourtCase> selected = readCase(connection, caseId);
            if (selected.isEmpty()) return CourtOperation.result(CourtActionResult.NOT_FOUND);
            if (!judgeAssigned(connection, caseId, judge)) {
                return CourtOperation.result(CourtActionResult.NOT_JUDGE);
            }
            if (target != null && !playerExists(connection, target)) {
                return CourtOperation.result(CourtActionResult.PLAYER_NOT_FOUND);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO court_orders(
                        case_id, judge_uuid, target_uuid, order_type, order_text, issued_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setLong(1, caseId);
                statement.setString(2, judge.toString());
                if (target == null) statement.setNull(3, java.sql.Types.VARCHAR);
                else statement.setString(3, target.toString());
                statement.setString(4, type.toLowerCase(java.util.Locale.ROOT));
                statement.setString(5, text);
                statement.setLong(6, now);
                if (expiresAt == null) statement.setNull(7, java.sql.Types.BIGINT);
                else statement.setLong(7, expiresAt);
                statement.executeUpdate();
            }
            docket(connection, caseId, judge, "ORDER_ISSUED", type + ": " + text, now);
            return CourtOperation.courtCase(selected.get());
        }
    }

    public CourtOperation issueWarrant(
            long caseId,
            UUID judge,
            UUID target,
            WarrantType type,
            String reason,
            int hours,
            long now
    ) throws SQLException {
        if (reason.isBlank() || reason.length() > 500 || hours < 1 || hours > 720) {
            return CourtOperation.result(CourtActionResult.INVALID_CONTENT);
        }
        try (Connection connection = database.openConnection()) {
            Optional<CourtCase> selected = readCase(connection, caseId);
            if (selected.isEmpty()) return CourtOperation.result(CourtActionResult.NOT_FOUND);
            if (selected.get().level() == CourtLevel.DISTRICT) {
                return CourtOperation.result(CourtActionResult.INVALID_JURISDICTION);
            }
            if (!judgeAssigned(connection, caseId, judge)) {
                return CourtOperation.result(CourtActionResult.NOT_JUDGE);
            }
            if (!playerExists(connection, target)) {
                return CourtOperation.result(CourtActionResult.PLAYER_NOT_FOUND);
            }
            long expiresAt = Math.addExact(now, Duration.ofHours(hours).toMillis());
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO court_warrants(
                        case_id, judge_uuid, target_uuid, warrant_type, reason, issued_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setLong(1, caseId);
                statement.setString(2, judge.toString());
                statement.setString(3, target.toString());
                statement.setString(4, type.name());
                statement.setString(5, reason);
                statement.setLong(6, now);
                statement.setLong(7, expiresAt);
                statement.executeUpdate();
            }
            docket(connection, caseId, judge, "WARRANT_ISSUED", type + ": " + reason, now);
            return CourtOperation.courtCase(selected.get());
        }
    }

    public CourtOperation verdict(
            long caseId,
            UUID judge,
            CourtOutcome outcome,
            long judgmentCents,
            long fineCents,
            int jailMinutes,
            String reasoning,
            long now
    ) throws SQLException {
        if (reasoning.isBlank() || reasoning.length() > 2_000
                || judgmentCents < 0 || fineCents < 0 || jailMinutes < 0) {
            return CourtOperation.result(CourtActionResult.INVALID_CONTENT);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<CourtCase> selected = readCase(connection, caseId);
                if (selected.isEmpty()) return rollback(connection, CourtActionResult.NOT_FOUND);
                CourtCase courtCase = selected.get();
                if (terminal(courtCase.status())) {
                    return rollback(connection, CourtActionResult.INVALID_STATE);
                }
                if (!judgeAssigned(connection, caseId, judge)) {
                    return rollback(connection, CourtActionResult.NOT_JUDGE);
                }
                CourtActionResult valid = validateVerdict(
                        courtCase, outcome, judgmentCents, fineCents, jailMinutes);
                if (valid != CourtActionResult.SUCCESS) return rollback(connection, valid);
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO court_verdict_votes(
                            case_id, judge_uuid, outcome, judgment_cents,
                            fine_cents, jail_minutes, reasoning, voted_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(case_id, judge_uuid) DO UPDATE SET
                            outcome = excluded.outcome,
                            judgment_cents = excluded.judgment_cents,
                            fine_cents = excluded.fine_cents,
                            jail_minutes = excluded.jail_minutes,
                            reasoning = excluded.reasoning,
                            voted_at = excluded.voted_at
                        """)) {
                    statement.setLong(1, caseId);
                    statement.setString(2, judge.toString());
                    statement.setString(3, outcome.name());
                    statement.setLong(4, judgmentCents);
                    statement.setLong(5, fineCents);
                    statement.setInt(6, jailMinutes);
                    statement.setString(7, reasoning);
                    statement.setLong(8, now);
                    statement.executeUpdate();
                }
                int matching = matchingVerdicts(
                        connection, caseId, outcome, judgmentCents, fineCents, jailMinutes);
                int required = courtCase.level() == CourtLevel.SUPREME ? 2 : 1;
                docket(connection, caseId, judge, "VERDICT_VOTE", outcome.name(), now);
                if (matching >= required) {
                    decide(connection, courtCase, outcome, judgmentCents, fineCents, jailMinutes, reasoning, now);
                }
                CourtCase updated = readCase(connection, caseId).orElseThrow();
                connection.commit();
                return CourtOperation.courtCase(updated);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public CourtOperation appeal(
            long sourceCaseId,
            UUID appellant,
            AppealGround ground,
            String argument,
            long now
    ) throws SQLException {
        if (argument.isBlank() || argument.length() > 1_000) {
            return CourtOperation.result(CourtActionResult.INVALID_CONTENT);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<CourtCase> selected = readCase(connection, sourceCaseId);
                if (selected.isEmpty()) return rollback(connection, CourtActionResult.NOT_FOUND);
                CourtCase source = selected.get();
                if (source.status() == CourtCaseStatus.APPEALED) {
                    return rollback(connection, CourtActionResult.ALREADY_APPEALED);
                }
                if (source.status() != CourtCaseStatus.DECIDED) {
                    return rollback(connection, CourtActionResult.NO_APPEAL);
                }
                if (!source.plaintiffId().equals(appellant) && !source.defendantId().equals(appellant)) {
                    return rollback(connection, CourtActionResult.NOT_PARTY);
                }
                CourtLevel target = switch (source.level()) {
                    case DISTRICT -> CourtLevel.FEDERAL;
                    case FEDERAL, SUPREME -> CourtLevel.SUPREME;
                };
                String argumentText = ground.name() + ": " + argument;
                CourtOperation created = insertCase(
                        connection, appellant, source.plaintiffId(), source.defendantId(),
                        target, CourtCaseType.APPEAL, "Appeal of " + source.number(),
                        argumentText, source.claimAmountCents(), sourceCaseId, now);
                setStatus(connection, sourceCaseId, CourtCaseStatus.APPEALED);
                docket(connection, sourceCaseId, appellant, "APPEAL_FILED",
                        created.courtCase().orElseThrow().number(), now);
                connection.commit();
                return created;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<CriminalRecord> criminalRecords(UUID player) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM criminal_records WHERE defendant_uuid = ?
                     ORDER BY convicted_at DESC, id DESC
                     """)) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<CriminalRecord> records = new ArrayList<>();
                while (results.next()) {
                    long pardoned = results.getLong("pardoned_at");
                    boolean isPardoned = !results.wasNull();
                    records.add(new CriminalRecord(
                            results.getLong("id"), results.getLong("case_id"),
                            results.getString("charge"), results.getLong("fine_cents"),
                            results.getInt("jail_minutes"),
                            Instant.ofEpochMilli(results.getLong("convicted_at")),
                            isPardoned ? Instant.ofEpochMilli(pardoned) : null));
                }
                return List.copyOf(records);
            }
        }
    }

    public List<CourtWarrant> activeWarrants(UUID target, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            expireWarrants(connection, now);
            String sql = """
                    SELECT w.*, j.last_name AS judge_name, t.last_name AS target_name
                    FROM court_warrants w
                    JOIN players j ON j.uuid = w.judge_uuid
                    JOIN players t ON t.uuid = w.target_uuid
                    WHERE w.target_uuid = ? AND w.status = 'ACTIVE' AND w.expires_at > ?
                    ORDER BY w.issued_at DESC, w.id DESC
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, target.toString());
                statement.setLong(2, now);
                try (ResultSet results = statement.executeQuery()) {
                    List<CourtWarrant> warrants = new ArrayList<>();
                    while (results.next()) warrants.add(readWarrant(results));
                    return List.copyOf(warrants);
                }
            }
        }
    }

    private CourtOperation file(
            UUID filer,
            UUID plaintiff,
            UUID defendant,
            CourtLevel level,
            CourtCaseType type,
            String title,
            String claim,
            long amount,
            Long parent,
            long now
    ) throws SQLException {
        if (filer.equals(defendant) || title.isBlank() || title.length() > 100
                || claim.isBlank() || claim.length() > 2_000) {
            return CourtOperation.result(CourtActionResult.INVALID_CONTENT);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!playerExists(connection, filer) || !playerExists(connection, defendant)) {
                    return rollback(connection, CourtActionResult.PLAYER_NOT_FOUND);
                }
                CourtOperation operation = insertCase(
                        connection, filer, plaintiff, defendant, level, type,
                        title, claim, amount, parent, now);
                connection.commit();
                return operation;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static CourtOperation insertCase(
            Connection connection,
            UUID filer,
            UUID plaintiff,
            UUID defendant,
            CourtLevel level,
            CourtCaseType type,
            String title,
            String claim,
            long amount,
            Long parent,
            long now
    ) throws SQLException {
        long id;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO court_cases(
                    court_level, case_type, status, parent_case_id, filer_uuid,
                    plaintiff_uuid, defendant_uuid, title, claim_text, claim_amount_cents, filed_at)
                VALUES (?, ?, 'FILED', ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, level.name());
            statement.setString(2, type.name());
            if (parent == null) statement.setNull(3, java.sql.Types.BIGINT);
            else statement.setLong(3, parent);
            statement.setString(4, filer.toString());
            statement.setString(5, plaintiff.toString());
            statement.setString(6, defendant.toString());
            statement.setString(7, title);
            statement.setString(8, claim);
            statement.setLong(9, amount);
            statement.setLong(10, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Case insert returned no id");
                id = keys.getLong(1);
            }
        }
        String number = caseNumber(id, now);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE court_cases SET case_number = ? WHERE id = ?")) {
            statement.setString(1, number);
            statement.setLong(2, id);
            statement.executeUpdate();
        }
        docket(connection, id, filer, "CASE_FILED", type.name(), now);
        return CourtOperation.courtCase(readCase(connection, id).orElseThrow());
    }

    private static CourtActionResult validateVerdict(
            CourtCase courtCase,
            CourtOutcome outcome,
            long judgment,
            long fine,
            int jail
    ) {
        return switch (courtCase.type()) {
            case CIVIL -> {
                if (outcome != CourtOutcome.LIABLE && outcome != CourtOutcome.NOT_LIABLE) {
                    yield CourtActionResult.INVALID_OUTCOME;
                }
                if (fine != 0 || jail != 0 || outcome == CourtOutcome.NOT_LIABLE && judgment != 0
                        || judgment > courtCase.claimAmountCents()) {
                    yield CourtActionResult.INVALID_SANCTION;
                }
                yield CourtActionResult.SUCCESS;
            }
            case CRIMINAL -> {
                if (outcome != CourtOutcome.GUILTY && outcome != CourtOutcome.NOT_GUILTY) {
                    yield CourtActionResult.INVALID_OUTCOME;
                }
                if (judgment != 0 || outcome == CourtOutcome.NOT_GUILTY && (fine != 0 || jail != 0)) {
                    yield CourtActionResult.INVALID_SANCTION;
                }
                if (courtCase.level() == CourtLevel.DISTRICT && (fine > 1_000_000L || jail > 60)) {
                    yield CourtActionResult.INVALID_JURISDICTION;
                }
                yield CourtActionResult.SUCCESS;
            }
            case CONSTITUTIONAL, INSTITUTIONAL ->
                    outcome == CourtOutcome.GRANTED || outcome == CourtOutcome.DENIED
                            ? judgment == 0 && fine == 0 && jail == 0
                            ? CourtActionResult.SUCCESS : CourtActionResult.INVALID_SANCTION
                            : CourtActionResult.INVALID_OUTCOME;
            case APPEAL -> outcome == CourtOutcome.AFFIRMED || outcome == CourtOutcome.REVERSED
                    ? judgment == 0 && fine == 0 && jail == 0
                    ? CourtActionResult.SUCCESS : CourtActionResult.INVALID_SANCTION
                    : CourtActionResult.INVALID_OUTCOME;
        };
    }

    private static void decide(
            Connection connection,
            CourtCase courtCase,
            CourtOutcome outcome,
            long judgment,
            long fine,
            int jail,
            String reasoning,
            long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE court_cases SET status = 'DECIDED', decided_at = ?, outcome = ?,
                    decision_text = ?, judgment_cents = ?, fine_cents = ?, jail_minutes = ?
                WHERE id = ?
                """)) {
            statement.setLong(1, now);
            statement.setString(2, outcome.name());
            statement.setString(3, reasoning);
            statement.setLong(4, judgment);
            statement.setLong(5, fine);
            statement.setInt(6, jail);
            statement.setLong(7, courtCase.id());
            statement.executeUpdate();
        }
        if (courtCase.type() == CourtCaseType.CRIMINAL && outcome == CourtOutcome.GUILTY) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT OR IGNORE INTO criminal_records(
                        case_id, defendant_uuid, charge, fine_cents, jail_minutes, convicted_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setLong(1, courtCase.id());
                statement.setString(2, courtCase.defendantId().toString());
                statement.setString(3, courtCase.claim());
                statement.setLong(4, fine);
                statement.setInt(5, jail);
                statement.setLong(6, now);
                statement.executeUpdate();
            }
        }
        docket(connection, courtCase.id(), null, "DECISION_ENTERED", outcome.name(), now);
    }

    private static int matchingVerdicts(
            Connection connection,
            long caseId,
            CourtOutcome outcome,
            long judgment,
            long fine,
            int jail
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM court_verdict_votes
                WHERE case_id = ? AND outcome = ? AND judgment_cents = ?
                    AND fine_cents = ? AND jail_minutes = ?
                """)) {
            statement.setLong(1, caseId);
            statement.setString(2, outcome.name());
            statement.setLong(3, judgment);
            statement.setLong(4, fine);
            statement.setInt(5, jail);
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getInt(1);
            }
        }
    }

    private static boolean canParticipate(
            Connection connection, CourtCase courtCase, UUID actor) throws SQLException {
        if (courtCase.plaintiffId().equals(actor) || courtCase.defendantId().equals(actor)
                || judgeAssigned(connection, courtCase.id(), actor)) return true;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM court_participants WHERE case_id = ? AND player_uuid = ? LIMIT 1
                """)) {
            statement.setLong(1, courtCase.id());
            statement.setString(2, actor.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static Optional<String> judicialRole(
            Connection connection, UUID judge, CourtLevel level) throws SQLException {
        List<String> roles = switch (level) {
            case DISTRICT -> List.of("magistrate");
            case FEDERAL -> List.of("judge");
            case SUPREME -> List.of("chief-justice", "justice");
        };
        for (String role : roles) if (hasJob(connection, judge, role)) return Optional.of(role);
        return Optional.empty();
    }

    private static boolean hasJob(Connection connection, UUID player, String job) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM citizen_jobs WHERE player_uuid = ? AND job_id = ? LIMIT 1
                """)) {
            statement.setString(1, player.toString());
            statement.setString(2, job);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static boolean playerExists(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE uuid = ?")) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static boolean judgeAssigned(Connection connection, long caseId, UUID judge) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM court_case_judges WHERE case_id = ? AND judge_uuid = ? LIMIT 1
                """)) {
            statement.setLong(1, caseId);
            statement.setString(2, judge.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static int judgeCount(Connection connection, long caseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM court_case_judges WHERE case_id = ?")) {
            statement.setLong(1, caseId);
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getInt(1);
            }
        }
    }

    private static Optional<CourtCase> readCase(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(baseCaseSelect() + " WHERE c.id = ?")) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readCase(results)) : Optional.empty();
            }
        }
    }

    private static String baseCaseSelect() {
        return """
                SELECT c.*, plaintiff.last_name AS plaintiff_name,
                    defendant.last_name AS defendant_name
                FROM court_cases c
                JOIN players plaintiff ON plaintiff.uuid = c.plaintiff_uuid
                JOIN players defendant ON defendant.uuid = c.defendant_uuid
                """;
    }

    private static CourtCase readCase(ResultSet results) throws SQLException {
        long parent = results.getLong("parent_case_id");
        boolean parentPresent = !results.wasNull();
        long scheduled = results.getLong("scheduled_at");
        boolean scheduledPresent = !results.wasNull();
        long decided = results.getLong("decided_at");
        boolean decidedPresent = !results.wasNull();
        String outcome = results.getString("outcome");
        return new CourtCase(
                results.getLong("id"), results.getString("case_number"),
                CourtLevel.valueOf(results.getString("court_level")),
                CourtCaseType.valueOf(results.getString("case_type")),
                CourtCaseStatus.valueOf(results.getString("status")),
                parentPresent ? parent : null, UUID.fromString(results.getString("filer_uuid")),
                UUID.fromString(results.getString("plaintiff_uuid")), results.getString("plaintiff_name"),
                UUID.fromString(results.getString("defendant_uuid")), results.getString("defendant_name"),
                results.getString("title"), results.getString("claim_text"),
                results.getLong("claim_amount_cents"), Instant.ofEpochMilli(results.getLong("filed_at")),
                scheduledPresent ? Instant.ofEpochMilli(scheduled) : null,
                decidedPresent ? Instant.ofEpochMilli(decided) : null,
                outcome == null ? null : CourtOutcome.valueOf(outcome), results.getString("decision_text"),
                results.getLong("judgment_cents"), results.getLong("fine_cents"),
                results.getInt("jail_minutes"));
    }

    private static Map<String, String> counsel(Connection connection, long caseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT cp.role, p.last_name FROM court_participants cp
                JOIN players p ON p.uuid = cp.player_uuid WHERE cp.case_id = ?
                """)) {
            statement.setLong(1, caseId);
            try (ResultSet results = statement.executeQuery()) {
                Map<String, String> counsel = new LinkedHashMap<>();
                while (results.next()) counsel.put(results.getString(1), results.getString(2));
                return Map.copyOf(counsel);
            }
        }
    }

    private static List<CourtJudge> judges(Connection connection, long caseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT j.*, p.last_name FROM court_case_judges j
                JOIN players p ON p.uuid = j.judge_uuid
                WHERE j.case_id = ? ORDER BY j.joined_at, j.judge_uuid
                """)) {
            statement.setLong(1, caseId);
            try (ResultSet results = statement.executeQuery()) {
                List<CourtJudge> judges = new ArrayList<>();
                while (results.next()) judges.add(new CourtJudge(
                        UUID.fromString(results.getString("judge_uuid")), results.getString("last_name"),
                        results.getString("judicial_role"),
                        Instant.ofEpochMilli(results.getLong("joined_at"))));
                return List.copyOf(judges);
            }
        }
    }

    private static List<CourtEvidence> evidence(Connection connection, long caseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.*, p.last_name FROM court_evidence e
                JOIN players p ON p.uuid = e.submitted_by
                WHERE e.case_id = ? ORDER BY e.submitted_at, e.id
                """)) {
            statement.setLong(1, caseId);
            try (ResultSet results = statement.executeQuery()) {
                List<CourtEvidence> evidence = new ArrayList<>();
                while (results.next()) evidence.add(new CourtEvidence(
                        results.getLong("id"), UUID.fromString(results.getString("submitted_by")),
                        results.getString("last_name"), results.getString("description"),
                        results.getBytes("item_data"), Instant.ofEpochMilli(results.getLong("submitted_at"))));
                return List.copyOf(evidence);
            }
        }
    }

    private static List<CourtDocketEntry> docket(Connection connection, long caseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT d.*, p.last_name FROM court_docket d
                LEFT JOIN players p ON p.uuid = d.actor_uuid
                WHERE d.case_id = ? ORDER BY d.created_at, d.id
                """)) {
            statement.setLong(1, caseId);
            try (ResultSet results = statement.executeQuery()) {
                List<CourtDocketEntry> entries = new ArrayList<>();
                while (results.next()) entries.add(new CourtDocketEntry(
                        results.getLong("id"), results.getString("last_name"),
                        results.getString("entry_type"), results.getString("entry_text"),
                        Instant.ofEpochMilli(results.getLong("created_at"))));
                return List.copyOf(entries);
            }
        }
    }

    private static List<CourtOrder> orders(Connection connection, long caseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT o.*, j.last_name AS judge_name, t.last_name AS target_name
                FROM court_orders o
                JOIN players j ON j.uuid = o.judge_uuid
                LEFT JOIN players t ON t.uuid = o.target_uuid
                WHERE o.case_id = ? ORDER BY o.issued_at, o.id
                """)) {
            statement.setLong(1, caseId);
            try (ResultSet results = statement.executeQuery()) {
                List<CourtOrder> orders = new ArrayList<>();
                while (results.next()) {
                    long expires = results.getLong("expires_at");
                    boolean expiryPresent = !results.wasNull();
                    orders.add(new CourtOrder(
                            results.getLong("id"), results.getString("judge_name"),
                            results.getString("target_name"), results.getString("order_type"),
                            results.getString("order_text"), Instant.ofEpochMilli(results.getLong("issued_at")),
                            expiryPresent ? Instant.ofEpochMilli(expires) : null, results.getString("status")));
                }
                return List.copyOf(orders);
            }
        }
    }

    private static List<CourtWarrant> warrants(Connection connection, long caseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT w.*, j.last_name AS judge_name, t.last_name AS target_name
                FROM court_warrants w
                JOIN players j ON j.uuid = w.judge_uuid
                JOIN players t ON t.uuid = w.target_uuid
                WHERE w.case_id = ? ORDER BY w.issued_at, w.id
                """)) {
            statement.setLong(1, caseId);
            try (ResultSet results = statement.executeQuery()) {
                List<CourtWarrant> warrants = new ArrayList<>();
                while (results.next()) warrants.add(readWarrant(results));
                return List.copyOf(warrants);
            }
        }
    }

    private static CourtWarrant readWarrant(ResultSet results) throws SQLException {
        return new CourtWarrant(
                results.getLong("id"), results.getLong("case_id"), results.getString("judge_name"),
                UUID.fromString(results.getString("target_uuid")), results.getString("target_name"),
                WarrantType.valueOf(results.getString("warrant_type")), results.getString("reason"),
                Instant.ofEpochMilli(results.getLong("issued_at")),
                Instant.ofEpochMilli(results.getLong("expires_at")), results.getString("status"));
    }

    private static void docket(
            Connection connection, long caseId, UUID actor, String type, String text, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO court_docket(case_id, actor_uuid, entry_type, entry_text, created_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, caseId);
            if (actor == null) statement.setNull(2, java.sql.Types.VARCHAR);
            else statement.setString(2, actor.toString());
            statement.setString(3, type);
            if (text == null) statement.setNull(4, java.sql.Types.VARCHAR);
            else statement.setString(4, text);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private static void setStatus(
            Connection connection, long caseId, CourtCaseStatus status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE court_cases SET status = ? WHERE id = ?")) {
            statement.setString(1, status.name());
            statement.setLong(2, caseId);
            statement.executeUpdate();
        }
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

    private static boolean terminal(CourtCaseStatus status) {
        return status == CourtCaseStatus.DECIDED || status == CourtCaseStatus.APPEALED
                || status == CourtCaseStatus.DISMISSED || status == CourtCaseStatus.CLOSED;
    }

    private static CourtOperation rollback(
            Connection connection, CourtActionResult result) throws SQLException {
        connection.rollback();
        return CourtOperation.result(result);
    }

    private static String caseNumber(long id, long now) {
        int year = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC).getYear();
        return "CV-%d-%04d".formatted(year, id);
    }
}
