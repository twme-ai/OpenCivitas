package dev.opencivitas.election;

import dev.opencivitas.database.Database;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ElectionRepository {
    private static final BigDecimal VOTE_MICROS = BigDecimal.valueOf(1_000_000L);

    private final Database database;

    public ElectionRepository(Database database) {
        this.database = database;
    }

    public ElectionOperation createOffice(
            UUID creator,
            String slug,
            ElectionDefinition definition,
            long now,
            long nominationsCloseAt,
            long votingClosesAt
    ) throws SQLException {
        if (nominationsCloseAt <= now || votingClosesAt <= nominationsCloseAt) {
            return ElectionOperation.result(ElectionActionResult.INVALID_TIMELINE);
        }
        try (Connection connection = database.openConnection()) {
            if (slugExists(connection, slug)) {
                return ElectionOperation.result(ElectionActionResult.SLUG_EXISTS);
            }
            long id = insertElection(
                    connection, creator, slug, definition.id(), ElectionKind.OFFICE,
                    definition.id(), definition.method(), definition.seats(), definition.termDays(),
                    definition.runningMateRequired(), definition.runningMateOffice(), now,
                    nominationsCloseAt, nominationsCloseAt, votingClosesAt);
            return ElectionOperation.election(readElection(connection, id).orElseThrow());
        }
    }

    public ElectionOperation createReferendum(
            UUID creator,
            String slug,
            String title,
            long now,
            long votingClosesAt
    ) throws SQLException {
        if (votingClosesAt <= now) {
            return ElectionOperation.result(ElectionActionResult.INVALID_TIMELINE);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (slugExists(connection, slug)) {
                    connection.rollback();
                    return ElectionOperation.result(ElectionActionResult.SLUG_EXISTS);
                }
                long id = insertElection(
                        connection, creator, slug, title, ElectionKind.REFERENDUM,
                        null, ElectionMethod.REFERENDUM, 1, 0, false, null,
                        now, now, now, votingClosesAt);
                insertChoice(connection, id, "yes", "Yes", null, null, null, now);
                insertChoice(connection, id, "no", "No", null, null, null, now);
                Election election = readElection(connection, id).orElseThrow();
                connection.commit();
                return ElectionOperation.election(election);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<Election> list(int limit, int offset) throws SQLException {
        String sql = "SELECT * FROM elections ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            statement.setInt(2, offset);
            try (ResultSet results = statement.executeQuery()) {
                List<Election> elections = new ArrayList<>();
                while (results.next()) elections.add(readElection(results));
                return List.copyOf(elections);
            }
        }
    }

    public Optional<ElectionDetails> details(long electionId) throws SQLException {
        try (Connection connection = database.openConnection()) {
            Optional<Election> election = readElection(connection, electionId);
            if (election.isEmpty()) return Optional.empty();
            return Optional.of(new ElectionDetails(
                    election.get(), readChoices(connection, electionId, false),
                    ballotCount(connection, electionId), readResults(connection, electionId)));
        }
    }

    public ElectionOperation nominate(
            long electionId,
            UUID candidate,
            UUID runningMate,
            ElectionDefinition definition,
            long now
    ) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Election> selected = readElection(connection, electionId);
                if (selected.isEmpty()) return rollback(connection, ElectionActionResult.NOT_FOUND);
                Election election = selected.get();
                if (election.kind() != ElectionKind.OFFICE
                        || !definition.id().equals(election.officeId())
                        || election.phase(now) != ElectionPhase.NOMINATIONS) {
                    return rollback(connection, ElectionActionResult.INVALID_PHASE);
                }
                if (election.runningMateRequired() && runningMate == null) {
                    return rollback(connection, ElectionActionResult.RUNNING_MATE_REQUIRED);
                }
                if (!election.runningMateRequired() && runningMate != null) {
                    return rollback(connection, ElectionActionResult.RUNNING_MATE_NOT_ALLOWED);
                }
                if (candidate.equals(runningMate)) {
                    return rollback(connection, ElectionActionResult.RUNNING_MATE_SELF);
                }

                Optional<CitizenEligibility> candidateProfile = eligibility(
                        connection, candidate, definition.recentWindow(), now);
                if (candidateProfile.isEmpty()) {
                    return rollback(connection, ElectionActionResult.PLAYER_NOT_FOUND);
                }
                ElectionActionResult eligible = validateEligibility(
                        connection, candidate, candidateProfile.get(), definition, now);
                if (eligible != ElectionActionResult.SUCCESS) return rollback(connection, eligible);

                String runningMateName = null;
                if (runningMate != null) {
                    Optional<CitizenEligibility> mateProfile = eligibility(
                            connection, runningMate, definition.recentWindow(), now);
                    if (mateProfile.isEmpty()) {
                        return rollback(connection, ElectionActionResult.RUNNING_MATE_NOT_FOUND);
                    }
                    ElectionActionResult mateEligible = validateRunningMateEligibility(
                            connection, runningMate, mateProfile.get(), definition, now);
                    if (mateEligible != ElectionActionResult.SUCCESS) {
                        return rollback(connection, mateEligible);
                    }
                    runningMateName = mateProfile.get().name();
                }
                if (activeCandidateExists(connection, electionId, candidate)) {
                    return rollback(connection, ElectionActionResult.ALREADY_NOMINATED);
                }
                upsertCandidate(
                        connection, electionId, candidate, candidateProfile.get().name(),
                        runningMate, runningMateName, now);
                connection.commit();
                return ElectionOperation.election(election);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public ElectionOperation withdraw(long electionId, UUID candidate, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Election> selected = readElection(connection, electionId);
                if (selected.isEmpty()) return rollback(connection, ElectionActionResult.NOT_FOUND);
                Election election = selected.get();
                if (election.phase(now) != ElectionPhase.NOMINATIONS) {
                    return rollback(connection, ElectionActionResult.INVALID_PHASE);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE election_choices SET status = 'WITHDRAWN'
                        WHERE election_id = ? AND candidate_uuid = ? AND status = 'ACTIVE'
                        """)) {
                    statement.setLong(1, electionId);
                    statement.setString(2, candidate.toString());
                    if (statement.executeUpdate() == 0) {
                        return rollback(connection, ElectionActionResult.NOT_NOMINATED);
                    }
                }
                connection.commit();
                return ElectionOperation.election(election);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public ElectionOperation castBallot(
            long electionId,
            UUID voter,
            List<String> preferences,
            long now
    ) throws SQLException {
        if (preferences.isEmpty()) return ElectionOperation.result(ElectionActionResult.EMPTY_BALLOT);
        if (new LinkedHashSet<>(preferences).size() != preferences.size()) {
            return ElectionOperation.result(ElectionActionResult.DUPLICATE_CHOICE);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Election> selected = readElection(connection, electionId);
                if (selected.isEmpty()) return rollback(connection, ElectionActionResult.NOT_FOUND);
                Election election = selected.get();
                if (election.phase(now) != ElectionPhase.VOTING) {
                    return rollback(connection, ElectionActionResult.INVALID_PHASE);
                }
                if (!playerExists(connection, voter)) {
                    return rollback(connection, ElectionActionResult.PLAYER_NOT_FOUND);
                }
                Set<String> choices = readChoices(connection, electionId, true).stream()
                        .map(ElectionChoice::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
                if (!choices.containsAll(preferences)) {
                    return rollback(connection, ElectionActionResult.INVALID_CHOICE);
                }
                String ballotId = ballotId(connection, electionId, voter).orElseGet(
                        () -> UUID.randomUUID().toString());
                upsertVoter(connection, electionId, voter, ballotId, now);
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM election_ballot_preferences WHERE ballot_id = ?")) {
                    delete.setString(1, ballotId);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO election_ballot_preferences(ballot_id, rank, choice_id)
                        VALUES (?, ?, ?)
                        """)) {
                    for (int index = 0; index < preferences.size(); index++) {
                        insert.setString(1, ballotId);
                        insert.setInt(2, index + 1);
                        insert.setString(3, preferences.get(index));
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
                return ElectionOperation.election(election);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public ElectionOperation close(long electionId, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<Election> selected = readElection(connection, electionId);
                if (selected.isEmpty()) return rollback(connection, ElectionActionResult.NOT_FOUND);
                Election election = selected.get();
                if (election.status() != ElectionStatus.OPEN) {
                    return rollback(connection, ElectionActionResult.ALREADY_CLOSED);
                }
                if (now < election.votingClosesAt().toEpochMilli()) {
                    return rollback(connection, ElectionActionResult.NOT_ENDED);
                }
                List<ElectionChoice> choices = readChoices(connection, electionId, true);
                List<RankedBallot> ballots = readBallots(connection, electionId);
                ElectionCount count = RankedChoiceCounter.count(
                        election.method(), choices.stream().map(ElectionChoice::id).toList(),
                        ballots, election.seats());
                persistCount(connection, electionId, choices, count);
                if (election.kind() == ElectionKind.OFFICE) {
                    createTerms(connection, election, choices, count.winners(), now);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE elections SET status = 'CLOSED', closed_at = ? WHERE id = ?")) {
                    statement.setLong(1, now);
                    statement.setLong(2, electionId);
                    statement.executeUpdate();
                }
                Election closed = readElection(connection, electionId).orElseThrow();
                connection.commit();
                return new ElectionOperation(
                        ElectionActionResult.SUCCESS, Optional.of(closed), Optional.of(count));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<ElectionOperation> closeDue(long now) throws SQLException {
        List<Long> due = new ArrayList<>();
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id FROM elections
                     WHERE status = 'OPEN' AND voting_closes_at <= ? ORDER BY id
                     """)) {
            statement.setLong(1, now);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) due.add(results.getLong(1));
            }
        }
        List<ElectionOperation> closed = new ArrayList<>();
        for (long electionId : due) closed.add(close(electionId, now));
        return List.copyOf(closed);
    }

    public ElectionOperation cancel(long electionId, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            Optional<Election> selected = readElection(connection, electionId);
            if (selected.isEmpty()) return ElectionOperation.result(ElectionActionResult.NOT_FOUND);
            if (selected.get().status() != ElectionStatus.OPEN) {
                return ElectionOperation.result(ElectionActionResult.ALREADY_CLOSED);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE elections SET status = 'CANCELLED', closed_at = ? WHERE id = ?")) {
                statement.setLong(1, now);
                statement.setLong(2, electionId);
                statement.executeUpdate();
            }
            return ElectionOperation.election(readElection(connection, electionId).orElseThrow());
        }
    }

    public List<OfficeTerm> activeTerms(long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            expireTerms(connection, now);
            String sql = """
                    SELECT t.*, p.last_name FROM office_terms t
                    JOIN players p ON p.uuid = t.holder_uuid
                    WHERE t.status = 'ACTIVE' AND t.ends_at > ?
                    ORDER BY t.office_id, t.seat_number
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, now);
                try (ResultSet results = statement.executeQuery()) {
                    List<OfficeTerm> terms = new ArrayList<>();
                    while (results.next()) terms.add(readTerm(results));
                    return List.copyOf(terms);
                }
            }
        }
    }

    private ElectionActionResult validateEligibility(
            Connection connection,
            UUID candidate,
            CitizenEligibility profile,
            ElectionDefinition definition,
            long now
    ) throws SQLException {
        if (now - profile.joinedAt() < definition.minimumCitizenship().toMillis()) {
            return ElectionActionResult.INELIGIBLE_CITIZENSHIP;
        }
        if (profile.totalPlaytime() < definition.minimumTotalPlaytime().toMillis()) {
            return ElectionActionResult.INELIGIBLE_TOTAL_PLAYTIME;
        }
        if (profile.recentPlaytime() < definition.minimumRecentPlaytime().toMillis()) {
            return ElectionActionResult.INELIGIBLE_RECENT_PLAYTIME;
        }
        if (definition.disallowImmediateReelection()
                && holdsOffice(connection, candidate, definition.id(), now)) {
            return ElectionActionResult.INELIGIBLE_REELECTION;
        }
        return ElectionActionResult.SUCCESS;
    }

    private static ElectionActionResult validateRunningMateEligibility(
            Connection connection,
            UUID runningMate,
            CitizenEligibility profile,
            ElectionDefinition definition,
            long now
    ) throws SQLException {
        if (now - profile.joinedAt() < definition.runningMateMinimumCitizenship().toMillis()) {
            return ElectionActionResult.RUNNING_MATE_INELIGIBLE_CITIZENSHIP;
        }
        if (profile.totalPlaytime() < definition.runningMateMinimumTotalPlaytime().toMillis()) {
            return ElectionActionResult.RUNNING_MATE_INELIGIBLE_TOTAL_PLAYTIME;
        }
        if (profile.recentPlaytime() < definition.runningMateMinimumRecentPlaytime().toMillis()) {
            return ElectionActionResult.RUNNING_MATE_INELIGIBLE_RECENT_PLAYTIME;
        }
        if (definition.runningMateDisallowMostRecentPresident()
                && wasMostRecentPresident(connection, runningMate)) {
            return ElectionActionResult.RUNNING_MATE_INELIGIBLE_HISTORY;
        }
        return ElectionActionResult.SUCCESS;
    }

    private static boolean wasMostRecentPresident(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT holder_uuid FROM office_terms
                WHERE office_id = 'president'
                ORDER BY started_at DESC, id DESC LIMIT 1
                """)) {
            try (ResultSet results = statement.executeQuery()) {
                return results.next() && player.toString().equals(results.getString(1));
            }
        }
    }

    private static Optional<CitizenEligibility> eligibility(
            Connection connection, UUID player, Duration recentWindow, long now) throws SQLException {
        long recentSince = now - recentWindow.toMillis();
        String sql = """
                SELECT p.last_name, p.joined_at,
                    COALESCE(SUM(s.last_activity_at - s.started_at), 0) AS total_millis,
                    COALESCE(SUM(CASE
                        WHEN s.last_activity_at IS NULL OR s.last_activity_at <= ? THEN 0
                        WHEN s.started_at < ? THEN s.last_activity_at - ?
                        ELSE s.last_activity_at - s.started_at
                    END), 0) AS recent_millis
                FROM players p
                LEFT JOIN player_activity_sessions s ON s.player_uuid = p.uuid
                WHERE p.uuid = ?
                GROUP BY p.uuid
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, recentSince);
            statement.setLong(2, recentSince);
            statement.setLong(3, recentSince);
            statement.setString(4, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) return Optional.empty();
                return Optional.of(new CitizenEligibility(
                        results.getString("last_name"), results.getLong("joined_at"),
                        results.getLong("total_millis"), results.getLong("recent_millis")));
            }
        }
    }

    private static boolean holdsOffice(
            Connection connection, UUID player, String office, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM office_terms
                WHERE holder_uuid = ? AND office_id = ? AND status = 'ACTIVE' AND ends_at > ?
                LIMIT 1
                """)) {
            statement.setString(1, player.toString());
            statement.setString(2, office);
            statement.setLong(3, now);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static void persistCount(
            Connection connection,
            long electionId,
            List<ElectionChoice> choices,
            ElectionCount count
    ) throws SQLException {
        try (PreparedStatement round = connection.prepareStatement("""
                INSERT INTO election_round_results(
                    election_id, round_number, choice_id, tally_micros, disposition)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            for (ElectionRound result : count.rounds()) {
                for (ElectionChoice choice : choices) {
                    round.setLong(1, electionId);
                    round.setInt(2, result.number());
                    round.setString(3, choice.id());
                    round.setLong(4, toMicros(result.tallies().getOrDefault(choice.id(), BigDecimal.ZERO)));
                    String disposition = choice.id().equals(result.elected()) ? "ELECTED"
                            : choice.id().equals(result.eliminated()) ? "ELIMINATED" : null;
                    if (disposition == null) round.setNull(5, java.sql.Types.VARCHAR);
                    else round.setString(5, disposition);
                    round.addBatch();
                }
            }
            round.executeBatch();
        }
        try (PreparedStatement result = connection.prepareStatement("""
                INSERT INTO election_results(
                    election_id, choice_id, placement, elected, final_tally_micros)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            Set<String> winners = Set.copyOf(count.winners());
            for (ElectionChoice choice : choices) {
                int ranking = count.ranking().indexOf(choice.id());
                result.setLong(1, electionId);
                result.setString(2, choice.id());
                result.setInt(3, ranking < 0 ? choices.size() : ranking + 1);
                result.setInt(4, winners.contains(choice.id()) ? 1 : 0);
                result.setLong(5, toMicros(reportedTally(choice.id(), count)));
                result.addBatch();
            }
            result.executeBatch();
        }
    }

    private static BigDecimal reportedTally(String choice, ElectionCount count) {
        for (int index = count.rounds().size() - 1; index >= 0; index--) {
            ElectionRound round = count.rounds().get(index);
            BigDecimal tally = round.tallies().get(choice);
            if (choice.equals(round.elected()) || choice.equals(round.eliminated())
                    || tally != null && tally.signum() > 0) {
                return tally == null ? BigDecimal.ZERO : tally;
            }
        }
        return count.finalTallies().getOrDefault(choice, BigDecimal.ZERO);
    }

    private static void createTerms(
            Connection connection,
            Election election,
            List<ElectionChoice> choices,
            List<String> winners,
            long now
    ) throws SQLException {
        expireTerms(connection, now);
        supersedeOffice(connection, election.officeId());
        long endsAt = Math.addExact(now, Duration.ofDays(election.termDays()).toMillis());
        Map<String, ElectionChoice> byId = choices.stream().collect(
                java.util.stream.Collectors.toMap(ElectionChoice::id, choice -> choice));
        int seat = 1;
        for (String winner : winners) {
            ElectionChoice choice = byId.get(winner);
            if (choice == null || choice.candidateId() == null) continue;
            insertTerm(connection, election.officeId(), seat++, choice.candidateId(), election.id(), now, endsAt);
            if (choice.runningMateId() != null && election.runningMateOffice() != null) {
                supersedeOffice(connection, election.runningMateOffice());
                insertTerm(connection, election.runningMateOffice(), 1,
                        choice.runningMateId(), election.id(), now, endsAt);
            }
        }
    }

    private static void insertTerm(
            Connection connection,
            String office,
            int seat,
            UUID holder,
            long electionId,
            long startsAt,
            long endsAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO office_terms(
                    office_id, seat_number, holder_uuid, election_id, started_at, ends_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, office);
            statement.setInt(2, seat);
            statement.setString(3, holder.toString());
            statement.setLong(4, electionId);
            statement.setLong(5, startsAt);
            statement.setLong(6, endsAt);
            statement.executeUpdate();
        }
    }

    private static void supersedeOffice(Connection connection, String office) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE office_terms SET status = 'SUPERSEDED' WHERE office_id = ? AND status = 'ACTIVE'")) {
            statement.setString(1, office);
            statement.executeUpdate();
        }
    }

    private static void expireTerms(Connection connection, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE office_terms SET status = 'EXPIRED'
                WHERE status = 'ACTIVE' AND ends_at <= ?
                """)) {
            statement.setLong(1, now);
            statement.executeUpdate();
        }
    }

    private static long insertElection(
            Connection connection,
            UUID creator,
            String slug,
            String title,
            ElectionKind kind,
            String office,
            ElectionMethod method,
            int seats,
            int termDays,
            boolean runningMateRequired,
            String runningMateOffice,
            long createdAt,
            long nominationsCloseAt,
            long votingOpensAt,
            long votingClosesAt
    ) throws SQLException {
        String sql = """
                INSERT INTO elections(
                    slug, title, kind, office_id, method, seats, term_days,
                    running_mate_required, running_mate_office, created_by, created_at,
                    nominations_close_at, voting_opens_at, voting_closes_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, slug);
            statement.setString(2, title);
            statement.setString(3, kind.name());
            if (office == null) statement.setNull(4, java.sql.Types.VARCHAR);
            else statement.setString(4, office);
            statement.setString(5, method.name());
            statement.setInt(6, seats);
            statement.setInt(7, termDays);
            statement.setInt(8, runningMateRequired ? 1 : 0);
            if (runningMateOffice == null) statement.setNull(9, java.sql.Types.VARCHAR);
            else statement.setString(9, runningMateOffice);
            if (creator == null) statement.setNull(10, java.sql.Types.VARCHAR);
            else statement.setString(10, creator.toString());
            statement.setLong(11, createdAt);
            statement.setLong(12, nominationsCloseAt);
            statement.setLong(13, votingOpensAt);
            statement.setLong(14, votingClosesAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Election insert returned no id");
                return keys.getLong(1);
            }
        }
    }

    private static void insertChoice(
            Connection connection,
            long electionId,
            String choiceId,
            String displayName,
            UUID candidate,
            UUID runningMate,
            String runningMateName,
            long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO election_choices(
                    election_id, choice_id, display_name, candidate_uuid,
                    running_mate_uuid, running_mate_name, nominated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, electionId);
            statement.setString(2, choiceId);
            statement.setString(3, displayName);
            if (candidate == null) statement.setNull(4, java.sql.Types.VARCHAR);
            else statement.setString(4, candidate.toString());
            if (runningMate == null) statement.setNull(5, java.sql.Types.VARCHAR);
            else statement.setString(5, runningMate.toString());
            if (runningMateName == null) statement.setNull(6, java.sql.Types.VARCHAR);
            else statement.setString(6, runningMateName);
            statement.setLong(7, now);
            statement.executeUpdate();
        }
    }

    private static void upsertCandidate(
            Connection connection,
            long electionId,
            UUID candidate,
            String candidateName,
            UUID runningMate,
            String runningMateName,
            long now
    ) throws SQLException {
        String sql = """
                INSERT INTO election_choices(
                    election_id, choice_id, display_name, candidate_uuid,
                    running_mate_uuid, running_mate_name, nominated_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                ON CONFLICT(election_id, choice_id) DO UPDATE SET
                    display_name = excluded.display_name,
                    running_mate_uuid = excluded.running_mate_uuid,
                    running_mate_name = excluded.running_mate_name,
                    nominated_at = excluded.nominated_at,
                    status = 'ACTIVE'
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, electionId);
            statement.setString(2, candidate.toString());
            statement.setString(3, candidateName);
            statement.setString(4, candidate.toString());
            if (runningMate == null) statement.setNull(5, java.sql.Types.VARCHAR);
            else statement.setString(5, runningMate.toString());
            if (runningMateName == null) statement.setNull(6, java.sql.Types.VARCHAR);
            else statement.setString(6, runningMateName);
            statement.setLong(7, now);
            statement.executeUpdate();
        }
    }

    private static Optional<Election> readElection(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM elections WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readElection(results)) : Optional.empty();
            }
        }
    }

    private static Election readElection(ResultSet results) throws SQLException {
        long closedAt = results.getLong("closed_at");
        boolean isOpen = results.wasNull();
        return new Election(
                results.getLong("id"), results.getString("slug"), results.getString("title"),
                ElectionKind.valueOf(results.getString("kind")), results.getString("office_id"),
                ElectionMethod.valueOf(results.getString("method")), results.getInt("seats"),
                results.getInt("term_days"), results.getInt("running_mate_required") == 1,
                results.getString("running_mate_office"), ElectionStatus.valueOf(results.getString("status")),
                Instant.ofEpochMilli(results.getLong("created_at")),
                Instant.ofEpochMilli(results.getLong("nominations_close_at")),
                Instant.ofEpochMilli(results.getLong("voting_opens_at")),
                Instant.ofEpochMilli(results.getLong("voting_closes_at")),
                isOpen ? null : Instant.ofEpochMilli(closedAt));
    }

    private static List<ElectionChoice> readChoices(
            Connection connection, long electionId, boolean activeOnly) throws SQLException {
        String sql = """
                SELECT * FROM election_choices WHERE election_id = ? %s
                ORDER BY display_name COLLATE NOCASE, choice_id
                """.formatted(activeOnly ? "AND status = 'ACTIVE'" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, electionId);
            try (ResultSet results = statement.executeQuery()) {
                List<ElectionChoice> choices = new ArrayList<>();
                while (results.next()) {
                    String candidate = results.getString("candidate_uuid");
                    String mate = results.getString("running_mate_uuid");
                    choices.add(new ElectionChoice(
                            results.getString("choice_id"), results.getString("display_name"),
                            candidate == null ? null : UUID.fromString(candidate),
                            mate == null ? null : UUID.fromString(mate),
                            results.getString("running_mate_name"),
                            Instant.ofEpochMilli(results.getLong("nominated_at")),
                            "ACTIVE".equals(results.getString("status"))));
                }
                return List.copyOf(choices);
            }
        }
    }

    private static List<RankedBallot> readBallots(Connection connection, long electionId) throws SQLException {
        String sql = """
                SELECT v.ballot_id, p.choice_id FROM election_voters v
                JOIN election_ballot_preferences p ON p.ballot_id = v.ballot_id
                WHERE v.election_id = ? ORDER BY v.ballot_id, p.rank
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, electionId);
            try (ResultSet results = statement.executeQuery()) {
                Map<String, List<String>> ballots = new LinkedHashMap<>();
                while (results.next()) {
                    ballots.computeIfAbsent(results.getString("ballot_id"), ignored -> new ArrayList<>())
                            .add(results.getString("choice_id"));
                }
                return ballots.values().stream().map(RankedBallot::new).toList();
            }
        }
    }

    private static List<ElectionResultEntry> readResults(
            Connection connection, long electionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM election_results WHERE election_id = ? ORDER BY placement, choice_id
                """)) {
            statement.setLong(1, electionId);
            try (ResultSet results = statement.executeQuery()) {
                List<ElectionResultEntry> entries = new ArrayList<>();
                while (results.next()) {
                    entries.add(new ElectionResultEntry(
                            results.getString("choice_id"), results.getInt("placement"),
                            results.getInt("elected") == 1,
                            BigDecimal.valueOf(results.getLong("final_tally_micros"), 6)));
                }
                return List.copyOf(entries);
            }
        }
    }

    private static int ballotCount(Connection connection, long electionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM election_voters WHERE election_id = ?")) {
            statement.setLong(1, electionId);
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getInt(1);
            }
        }
    }

    private static Optional<String> ballotId(
            Connection connection, long electionId, UUID voter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ballot_id FROM election_voters WHERE election_id = ? AND voter_uuid = ?
                """)) {
            statement.setLong(1, electionId);
            statement.setString(2, voter.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(results.getString(1)) : Optional.empty();
            }
        }
    }

    private static void upsertVoter(
            Connection connection, long electionId, UUID voter, String ballotId, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO election_voters(election_id, voter_uuid, ballot_id, cast_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(election_id, voter_uuid) DO UPDATE SET cast_at = excluded.cast_at
                """)) {
            statement.setLong(1, electionId);
            statement.setString(2, voter.toString());
            statement.setString(3, ballotId);
            statement.setLong(4, now);
            statement.executeUpdate();
        }
    }

    private static boolean activeCandidateExists(
            Connection connection, long electionId, UUID candidate) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM election_choices
                WHERE election_id = ? AND candidate_uuid = ? AND status = 'ACTIVE'
                """)) {
            statement.setLong(1, electionId);
            statement.setString(2, candidate.toString());
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

    private static boolean slugExists(Connection connection, String slug) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM elections WHERE slug = ? COLLATE NOCASE")) {
            statement.setString(1, slug);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static OfficeTerm readTerm(ResultSet results) throws SQLException {
        long electionId = results.getLong("election_id");
        boolean appointed = !results.wasNull();
        return new OfficeTerm(
                results.getLong("id"), results.getString("office_id"), results.getInt("seat_number"),
                UUID.fromString(results.getString("holder_uuid")), results.getString("last_name"),
                appointed ? electionId : null,
                Instant.ofEpochMilli(results.getLong("started_at")),
                Instant.ofEpochMilli(results.getLong("ends_at")));
    }

    private static ElectionOperation rollback(
            Connection connection, ElectionActionResult result) throws SQLException {
        connection.rollback();
        return ElectionOperation.result(result);
    }

    private static long toMicros(BigDecimal votes) {
        return votes.multiply(VOTE_MICROS).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    }

    private record CitizenEligibility(
            String name,
            long joinedAt,
            long totalPlaytime,
            long recentPlaytime
    ) {
    }
}
