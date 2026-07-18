package dev.opencivitas.legislature;

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class LegislatureRepository {
    private static final String CONSTITUTIONAL_TITLE = "a bill to amend the constitution";

    private final Database database;
    private final Duration presidentialActionWindow;

    public LegislatureRepository(Database database, Duration presidentialActionWindow) {
        this.database = database;
        this.presidentialActionWindow = presidentialActionWindow;
    }

    public List<LegislativeBill> list(int limit, int offset) throws SQLException {
        String sql = """
                SELECT b.*, p.last_name AS author_name FROM legislative_bills b
                JOIN players p ON p.uuid = b.author_uuid
                ORDER BY b.created_at DESC, b.id DESC LIMIT ? OFFSET ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            statement.setInt(2, offset);
            try (ResultSet results = statement.executeQuery()) {
                List<LegislativeBill> bills = new ArrayList<>();
                while (results.next()) bills.add(readBill(results));
                return List.copyOf(bills);
            }
        }
    }

    public Optional<LegislativeDetails> details(long billId) throws SQLException {
        try (Connection connection = database.openConnection()) {
            Optional<LegislativeBill> bill = readBill(connection, billId);
            if (bill.isEmpty()) return Optional.empty();
            return Optional.of(new LegislativeDetails(
                    bill.get(), amendments(connection, billId),
                    voteResults(connection, billId), events(connection, billId)));
        }
    }

    public List<EnactedLaw> laws(int limit, int offset) throws SQLException {
        String sql = """
                SELECT * FROM enacted_laws WHERE repealed_at IS NULL
                ORDER BY enacted_at DESC, id DESC LIMIT ? OFFSET ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            statement.setInt(2, offset);
            try (ResultSet results = statement.executeQuery()) {
                List<EnactedLaw> laws = new ArrayList<>();
                while (results.next()) laws.add(readLaw(results));
                return List.copyOf(laws);
            }
        }
    }

    public Optional<EnactedLaw> findLaw(String numberOrId) throws SQLException {
        Long id = null;
        try {
            id = Long.parseLong(numberOrId);
        } catch (NumberFormatException ignored) {
            // Law numbers are the normal lookup path.
        }
        String sql = id == null
                ? "SELECT * FROM enacted_laws WHERE law_number = ? COLLATE NOCASE AND repealed_at IS NULL"
                : "SELECT * FROM enacted_laws WHERE id = ? AND repealed_at IS NULL";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (id == null) statement.setString(1, numberOrId);
            else statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readLaw(results)) : Optional.empty();
            }
        }
    }

    public LegislativeOperation createDraft(
            UUID author,
            BillType type,
            String title,
            String body,
            long now
    ) throws SQLException {
        if (title.isBlank() || title.length() > 100 || body.isBlank() || body.length() > 4_000
                || type == BillType.CONSTITUTIONAL
                && !title.toLowerCase(java.util.Locale.ROOT).startsWith(CONSTITUTIONAL_TITLE)) {
            return LegislativeOperation.result(LegislativeActionResult.INVALID_CONTENT);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!holdsChamber(connection, author, LegislativeChamber.HOUSE, now)) {
                    return rollback(connection, LegislativeActionResult.NOT_HOUSE_MEMBER);
                }
                long id;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO legislative_bills(
                            bill_type, title, body, author_uuid, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'DRAFT', ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, type.name());
                    statement.setString(2, title);
                    statement.setString(3, body);
                    statement.setString(4, author.toString());
                    statement.setLong(5, now);
                    statement.setLong(6, now);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Bill insert returned no id");
                        id = keys.getLong(1);
                    }
                }
                String number = billNumber(type, id, now);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE legislative_bills SET bill_number = ? WHERE id = ?")) {
                    statement.setString(1, number);
                    statement.setLong(2, id);
                    statement.executeUpdate();
                }
                event(connection, id, author, "DRAFT_CREATED", type.name(), now);
                LegislativeBill bill = readBill(connection, id).orElseThrow();
                connection.commit();
                return LegislativeOperation.bill(bill);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public LegislativeOperation submit(long billId, UUID actor, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<LegislativeBill> selected = readBill(connection, billId);
                if (selected.isEmpty()) return rollback(connection, LegislativeActionResult.NOT_FOUND);
                LegislativeBill bill = selected.get();
                if (bill.status() != BillStatus.DRAFT) {
                    return rollback(connection, LegislativeActionResult.INVALID_STATE);
                }
                if (!bill.authorId().equals(actor)) {
                    return rollback(connection, LegislativeActionResult.NOT_AUTHOR);
                }
                if (!holdsChamber(connection, actor, LegislativeChamber.HOUSE, now)) {
                    return rollback(connection, LegislativeActionResult.NOT_HOUSE_MEMBER);
                }
                transition(connection, billId, BillStatus.HOUSE_VOTING, now, null);
                event(connection, billId, actor, "HOUSE_VOTE_OPENED", null, now);
                LegislativeBill updated = readBill(connection, billId).orElseThrow();
                connection.commit();
                return LegislativeOperation.bill(updated);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public LegislativeOperation withdraw(long billId, UUID actor, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<LegislativeBill> selected = readBill(connection, billId);
                if (selected.isEmpty()) return rollback(connection, LegislativeActionResult.NOT_FOUND);
                if (selected.get().status() != BillStatus.DRAFT) {
                    return rollback(connection, LegislativeActionResult.INVALID_STATE);
                }
                if (!selected.get().authorId().equals(actor)) {
                    return rollback(connection, LegislativeActionResult.NOT_AUTHOR);
                }
                transition(connection, billId, BillStatus.WITHDRAWN, now, null);
                event(connection, billId, actor, "WITHDRAWN", null, now);
                LegislativeBill bill = readBill(connection, billId).orElseThrow();
                connection.commit();
                return LegislativeOperation.bill(bill);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public LegislativeOperation amend(long billId, UUID author, String text, long now) throws SQLException {
        if (text.isBlank() || text.length() > 1_000) {
            return LegislativeOperation.result(LegislativeActionResult.INVALID_CONTENT);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<LegislativeBill> selected = readBill(connection, billId);
                if (selected.isEmpty()) return rollback(connection, LegislativeActionResult.NOT_FOUND);
                if (selected.get().status() != BillStatus.SENATE_VOTING) {
                    return rollback(connection, LegislativeActionResult.INVALID_STATE);
                }
                if (!holdsChamber(connection, author, LegislativeChamber.SENATE, now)) {
                    return rollback(connection, LegislativeActionResult.NOT_SENATE_MEMBER);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO legislative_amendments(
                            bill_id, author_uuid, amendment_text, created_at)
                        VALUES (?, ?, ?, ?)
                        """)) {
                    statement.setLong(1, billId);
                    statement.setString(2, author.toString());
                    statement.setString(3, text);
                    statement.setLong(4, now);
                    statement.executeUpdate();
                }
                event(connection, billId, author, "SENATE_AMENDMENT_PROPOSED", text, now);
                LegislativeBill bill = readBill(connection, billId).orElseThrow();
                connection.commit();
                return LegislativeOperation.bill(bill);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public LegislativeOperation vote(
            long billId,
            UUID voter,
            LegislativeVote vote,
            long now
    ) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<LegislativeBill> selected = readBill(connection, billId);
                if (selected.isEmpty()) return rollback(connection, LegislativeActionResult.NOT_FOUND);
                LegislativeBill bill = selected.get();
                Optional<LegislativeChamber> chamber = chamber(bill.status());
                if (chamber.isEmpty()) {
                    return rollback(connection, LegislativeActionResult.INVALID_STATE);
                }
                if (!holdsChamber(connection, voter, chamber.get(), now)) {
                    return rollback(connection, chamber.get() == LegislativeChamber.HOUSE
                            ? LegislativeActionResult.NOT_HOUSE_MEMBER
                            : LegislativeActionResult.NOT_SENATE_MEMBER);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO legislative_votes(bill_id, stage, voter_uuid, vote, cast_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT(bill_id, stage, voter_uuid) DO UPDATE SET
                            vote = excluded.vote, cast_at = excluded.cast_at
                        """)) {
                    statement.setLong(1, billId);
                    statement.setString(2, bill.status().name());
                    statement.setString(3, voter.toString());
                    statement.setString(4, vote.name());
                    statement.setLong(5, now);
                    statement.executeUpdate();
                }
                event(connection, billId, voter, "VOTE_CAST", bill.status() + ":" + vote, now);
                connection.commit();
                return LegislativeOperation.bill(bill);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public LegislativeOperation tally(long billId, UUID actor, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<LegislativeBill> selected = readBill(connection, billId);
                if (selected.isEmpty()) return rollback(connection, LegislativeActionResult.NOT_FOUND);
                LegislativeBill bill = selected.get();
                Optional<LegislativeChamber> chamber = chamber(bill.status());
                if (chamber.isEmpty()) {
                    return rollback(connection, LegislativeActionResult.INVALID_STATE);
                }
                if (!holdsChamber(connection, actor, chamber.get(), now)) {
                    return rollback(connection, chamber.get() == LegislativeChamber.HOUSE
                            ? LegislativeActionResult.NOT_HOUSE_MEMBER
                            : LegislativeActionResult.NOT_SENATE_MEMBER);
                }
                VoteCounts counts = voteCounts(connection, billId, bill.status());
                VoteThreshold threshold = threshold(bill);
                VoteDecision decision = LegislativeVoteCalculator.decide(
                        chamber.get(), threshold, counts.yes(), counts.no(), counts.abstain());
                LegislativeVoteResult result = new LegislativeVoteResult(
                        bill.status(), counts.yes(), counts.no(), counts.abstain(),
                        decision.quorumRequired(), threshold, decision.passed(), Instant.ofEpochMilli(now));
                insertVoteResult(connection, billId, result);

                if (decision.passed()) {
                    passed(connection, bill, actor, now);
                } else {
                    failed(connection, bill, actor, decision.quorumMet(), now);
                }
                LegislativeBill updated = readBill(connection, billId).orElseThrow();
                connection.commit();
                return new LegislativeOperation(
                        LegislativeActionResult.SUCCESS, Optional.of(updated), Optional.of(result));
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public LegislativeOperation presidentialAction(
            long billId,
            UUID president,
            boolean assent,
            String reason,
            long now
    ) throws SQLException {
        if (!assent && (reason == null || reason.isBlank() || reason.length() > 500)) {
            return LegislativeOperation.result(LegislativeActionResult.INVALID_CONTENT);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<LegislativeBill> selected = readBill(connection, billId);
                if (selected.isEmpty()) return rollback(connection, LegislativeActionResult.NOT_FOUND);
                LegislativeBill bill = selected.get();
                if (bill.status() != BillStatus.PRESIDENT_REVIEW) {
                    return rollback(connection, LegislativeActionResult.INVALID_STATE);
                }
                if (bill.presidentialDeadline() != null
                        && now >= bill.presidentialDeadline().toEpochMilli()) {
                    assent(connection, bill, null, now, true);
                    LegislativeBill updated = readBill(connection, billId).orElseThrow();
                    connection.commit();
                    return new LegislativeOperation(
                            LegislativeActionResult.PRESIDENTIAL_WINDOW_EXPIRED,
                            Optional.of(updated), Optional.empty());
                }
                if (!holdsOffice(connection, president, "president", now)) {
                    return rollback(connection, LegislativeActionResult.NOT_PRESIDENT);
                }
                if (assent) {
                    assent(connection, bill, president, now, false);
                } else {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE legislative_bills SET status = 'VETOED', veto_reason = ?,
                                presidential_deadline = NULL, updated_at = ? WHERE id = ?
                            """)) {
                        statement.setString(1, reason);
                        statement.setLong(2, now);
                        statement.setLong(3, billId);
                        statement.executeUpdate();
                    }
                    event(connection, billId, president, "PRESIDENTIAL_VETO", reason, now);
                }
                LegislativeBill updated = readBill(connection, billId).orElseThrow();
                connection.commit();
                return LegislativeOperation.bill(updated);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public LegislativeOperation startOverride(long billId, UUID actor, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<LegislativeBill> selected = readBill(connection, billId);
                if (selected.isEmpty()) return rollback(connection, LegislativeActionResult.NOT_FOUND);
                LegislativeBill bill = selected.get();
                if (bill.status() != BillStatus.VETOED) {
                    return rollback(connection, LegislativeActionResult.INVALID_STATE);
                }
                if (bill.type() == BillType.APPROPRIATION) {
                    return rollback(connection, LegislativeActionResult.APPROPRIATION_OVERRIDE_FORBIDDEN);
                }
                if (!holdsChamber(connection, actor, LegislativeChamber.HOUSE, now)) {
                    return rollback(connection, LegislativeActionResult.NOT_HOUSE_MEMBER);
                }
                transition(connection, billId, BillStatus.HOUSE_OVERRIDE, now, null);
                event(connection, billId, actor, "VETO_OVERRIDE_OPENED", null, now);
                LegislativeBill updated = readBill(connection, billId).orElseThrow();
                connection.commit();
                return LegislativeOperation.bill(updated);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<LegislativeBill> autoAssent(long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                List<LegislativeBill> due = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT b.*, p.last_name AS author_name FROM legislative_bills b
                        JOIN players p ON p.uuid = b.author_uuid
                        WHERE b.status = 'PRESIDENT_REVIEW' AND b.presidential_deadline <= ?
                        ORDER BY b.id
                        """)) {
                    statement.setLong(1, now);
                    try (ResultSet results = statement.executeQuery()) {
                        while (results.next()) due.add(readBill(results));
                    }
                }
                for (LegislativeBill bill : due) assent(connection, bill, null, now, true);
                List<LegislativeBill> updated = new ArrayList<>();
                for (LegislativeBill bill : due) updated.add(readBill(connection, bill.id()).orElseThrow());
                connection.commit();
                return List.copyOf(updated);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<LegislativeBill> pendingReferendums() throws SQLException {
        return billsByStatus(BillStatus.REFERENDUM_REQUIRED);
    }

    public List<LegislativeBill> activeReferendums() throws SQLException {
        return billsByStatus(BillStatus.REFERENDUM);
    }

    public LegislativeOperation linkReferendum(
            long billId, long electionId, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            Optional<LegislativeBill> selected = readBill(connection, billId);
            if (selected.isEmpty()) return LegislativeOperation.result(LegislativeActionResult.NOT_FOUND);
            if (selected.get().status() != BillStatus.REFERENDUM_REQUIRED) {
                return LegislativeOperation.result(LegislativeActionResult.INVALID_STATE);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE legislative_bills SET status = 'REFERENDUM', referendum_election_id = ?,
                        updated_at = ? WHERE id = ?
                    """)) {
                statement.setLong(1, electionId);
                statement.setLong(2, now);
                statement.setLong(3, billId);
                statement.executeUpdate();
            }
            event(connection, billId, null, "CONSTITUTIONAL_REFERENDUM_OPENED",
                    Long.toString(electionId), now);
            return LegislativeOperation.bill(readBill(connection, billId).orElseThrow());
        }
    }

    public LegislativeOperation settleReferendum(
            long billId,
            long electionId,
            int yes,
            int no,
            long now
    ) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<LegislativeBill> selected = readBill(connection, billId);
                if (selected.isEmpty()) return rollback(connection, LegislativeActionResult.NOT_FOUND);
                LegislativeBill bill = selected.get();
                if (bill.status() != BillStatus.REFERENDUM
                        || bill.referendumElectionId() == null
                        || bill.referendumElectionId() != electionId) {
                    return rollback(connection, LegislativeActionResult.INVALID_STATE);
                }
                boolean passed = yes + no > 0 && yes * 3L >= (yes + no) * 2L;
                if (passed) {
                    enact(connection, bill, now);
                    event(connection, billId, null, "REFERENDUM_PASSED", yes + ":" + no, now);
                } else {
                    transition(connection, billId, BillStatus.REJECTED, now, null);
                    event(connection, billId, null, "REFERENDUM_FAILED", yes + ":" + no, now);
                }
                LegislativeBill updated = readBill(connection, billId).orElseThrow();
                connection.commit();
                return LegislativeOperation.bill(updated);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void passed(
            Connection connection,
            LegislativeBill bill,
            UUID actor,
            long now
    ) throws SQLException {
        switch (bill.status()) {
            case HOUSE_VOTING -> {
                if (bill.type() == BillType.RESOLUTION) {
                    enact(connection, bill, now);
                    event(connection, bill.id(), actor, "HOUSE_RESOLUTION_PASSED", null, now);
                } else {
                    transition(connection, bill.id(), BillStatus.SENATE_VOTING, now, null);
                    event(connection, bill.id(), actor, "HOUSE_PASSED", null, now);
                }
            }
            case SENATE_VOTING -> {
                if (hasProposedAmendments(connection, bill.id())) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE legislative_amendments SET status = 'ADOPTED'
                            WHERE bill_id = ? AND status = 'PROPOSED'
                            """)) {
                        statement.setLong(1, bill.id());
                        statement.executeUpdate();
                    }
                    transition(connection, bill.id(), BillStatus.HOUSE_RECONSIDERATION, now, null);
                    event(connection, bill.id(), actor, "SENATE_PASSED_WITH_AMENDMENTS", null, now);
                } else {
                    congressionalPass(connection, bill, actor, now);
                }
            }
            case HOUSE_RECONSIDERATION -> congressionalPass(connection, bill, actor, now);
            case HOUSE_OVERRIDE -> {
                transition(connection, bill.id(), BillStatus.SENATE_OVERRIDE, now, null);
                event(connection, bill.id(), actor, "HOUSE_OVERRIDE_PASSED", null, now);
            }
            case SENATE_OVERRIDE -> {
                if (bill.type() == BillType.CONSTITUTIONAL) {
                    transition(connection, bill.id(), BillStatus.REFERENDUM_REQUIRED, now, null);
                } else {
                    enact(connection, bill, now);
                }
                event(connection, bill.id(), actor, "VETO_OVERRIDDEN", null, now);
            }
            default -> throw new IllegalStateException("Cannot pass bill from " + bill.status());
        }
    }

    private void failed(
            Connection connection,
            LegislativeBill bill,
            UUID actor,
            boolean quorum,
            long now
    ) throws SQLException {
        BillStatus terminal = bill.status() == BillStatus.HOUSE_OVERRIDE
                || bill.status() == BillStatus.SENATE_OVERRIDE
                ? BillStatus.VETO_UPHELD : BillStatus.REJECTED;
        if (bill.status() == BillStatus.SENATE_VOTING) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE legislative_amendments SET status = 'REJECTED'
                    WHERE bill_id = ? AND status = 'PROPOSED'
                    """)) {
                statement.setLong(1, bill.id());
                statement.executeUpdate();
            }
        }
        transition(connection, bill.id(), terminal, now, null);
        event(connection, bill.id(), actor, quorum ? "VOTE_FAILED" : "QUORUM_FAILED",
                bill.status().name(), now);
    }

    private void congressionalPass(
            Connection connection,
            LegislativeBill bill,
            UUID actor,
            long now
    ) throws SQLException {
        long deadline = Math.addExact(now, presidentialActionWindow.toMillis());
        transition(connection, bill.id(), BillStatus.PRESIDENT_REVIEW, now, deadline);
        event(connection, bill.id(), actor, "CONGRESS_PASSED", null, now);
    }

    private void assent(
            Connection connection,
            LegislativeBill bill,
            UUID president,
            long now,
            boolean assumed
    ) throws SQLException {
        if (bill.type() == BillType.CONSTITUTIONAL) {
            transition(connection, bill.id(), BillStatus.REFERENDUM_REQUIRED, now, null);
        } else {
            enact(connection, bill, now);
        }
        event(connection, bill.id(), president,
                assumed ? "PRESIDENTIAL_ASSENT_ASSUMED" : "PRESIDENTIAL_ASSENT", null, now);
    }

    private static void enact(Connection connection, LegislativeBill bill, long now) throws SQLException {
        transition(connection, bill.id(), BillStatus.ENACTED, now, null);
        try (PreparedStatement enacted = connection.prepareStatement(
                "UPDATE legislative_bills SET enacted_at = ? WHERE id = ?")) {
            enacted.setLong(1, now);
            enacted.setLong(2, bill.id());
            enacted.executeUpdate();
        }
        if (bill.type() == BillType.RESOLUTION) return;
        String body = enactedBody(connection, bill);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO enacted_laws(
                    bill_id, law_number, title, body, law_type, enacted_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, bill.id());
            statement.setString(2, bill.number());
            statement.setString(3, bill.title());
            statement.setString(4, body);
            statement.setString(5, bill.type().name());
            statement.setLong(6, now);
            statement.executeUpdate();
        }
    }

    private static String enactedBody(Connection connection, LegislativeBill bill) throws SQLException {
        List<String> adopted = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT amendment_text FROM legislative_amendments
                WHERE bill_id = ? AND status = 'ADOPTED' ORDER BY id
                """)) {
            statement.setLong(1, bill.id());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) adopted.add(results.getString(1));
            }
        }
        if (adopted.isEmpty()) return bill.body();
        return bill.body() + "\n\nAdopted amendments:\n- " + String.join("\n- ", adopted);
    }

    private static VoteThreshold threshold(LegislativeBill bill) {
        if (bill.status() == BillStatus.HOUSE_OVERRIDE || bill.status() == BillStatus.SENATE_OVERRIDE) {
            return bill.type() == BillType.CONSTITUTIONAL
                    ? VoteThreshold.FOUR_FIFTHS : VoteThreshold.TWO_THIRDS;
        }
        return bill.type() == BillType.CONSTITUTIONAL
                ? VoteThreshold.TWO_THIRDS : VoteThreshold.SIMPLE;
    }

    private static Optional<LegislativeChamber> chamber(BillStatus status) {
        return switch (status) {
            case HOUSE_VOTING, HOUSE_RECONSIDERATION, HOUSE_OVERRIDE ->
                    Optional.of(LegislativeChamber.HOUSE);
            case SENATE_VOTING, SENATE_OVERRIDE -> Optional.of(LegislativeChamber.SENATE);
            default -> Optional.empty();
        };
    }

    private static boolean holdsChamber(
            Connection connection,
            UUID player,
            LegislativeChamber chamber,
            long now
    ) throws SQLException {
        String predicate = chamber == LegislativeChamber.HOUSE
                ? "office_id = 'house'" : "office_id IN ('senate-a', 'senate-b')";
        String sql = """
                SELECT 1 FROM office_terms WHERE holder_uuid = ? AND status = 'ACTIVE'
                    AND ends_at > ? AND %s LIMIT 1
                """.formatted(predicate);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            statement.setLong(2, now);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static boolean holdsOffice(
            Connection connection, UUID player, String office, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM office_terms WHERE holder_uuid = ? AND office_id = ?
                    AND status = 'ACTIVE' AND ends_at > ? LIMIT 1
                """)) {
            statement.setString(1, player.toString());
            statement.setString(2, office);
            statement.setLong(3, now);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static VoteCounts voteCounts(
            Connection connection, long billId, BillStatus stage) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT
                    SUM(CASE WHEN vote = 'YES' THEN 1 ELSE 0 END) AS yes_votes,
                    SUM(CASE WHEN vote = 'NO' THEN 1 ELSE 0 END) AS no_votes,
                    SUM(CASE WHEN vote = 'ABSTAIN' THEN 1 ELSE 0 END) AS abstain_votes
                FROM legislative_votes WHERE bill_id = ? AND stage = ?
                """)) {
            statement.setLong(1, billId);
            statement.setString(2, stage.name());
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return new VoteCounts(
                        results.getInt("yes_votes"), results.getInt("no_votes"),
                        results.getInt("abstain_votes"));
            }
        }
    }

    private static void insertVoteResult(
            Connection connection, long billId, LegislativeVoteResult result) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO legislative_vote_results(
                    bill_id, stage, yes_votes, no_votes, abstain_votes,
                    quorum_required, threshold, passed, tallied_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, billId);
            statement.setString(2, result.stage().name());
            statement.setInt(3, result.yesVotes());
            statement.setInt(4, result.noVotes());
            statement.setInt(5, result.abstainVotes());
            statement.setInt(6, result.quorumRequired());
            statement.setString(7, result.threshold().name());
            statement.setInt(8, result.passed() ? 1 : 0);
            statement.setLong(9, result.talliedAt().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private static boolean hasProposedAmendments(Connection connection, long billId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM legislative_amendments
                WHERE bill_id = ? AND status = 'PROPOSED' LIMIT 1
                """)) {
            statement.setLong(1, billId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private List<LegislativeBill> billsByStatus(BillStatus status) throws SQLException {
        String sql = """
                SELECT b.*, p.last_name AS author_name FROM legislative_bills b
                JOIN players p ON p.uuid = b.author_uuid
                WHERE b.status = ? ORDER BY b.id
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            try (ResultSet results = statement.executeQuery()) {
                List<LegislativeBill> bills = new ArrayList<>();
                while (results.next()) bills.add(readBill(results));
                return List.copyOf(bills);
            }
        }
    }

    private static Optional<LegislativeBill> readBill(Connection connection, long billId) throws SQLException {
        String sql = """
                SELECT b.*, p.last_name AS author_name FROM legislative_bills b
                JOIN players p ON p.uuid = b.author_uuid WHERE b.id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, billId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readBill(results)) : Optional.empty();
            }
        }
    }

    private static LegislativeBill readBill(ResultSet results) throws SQLException {
        long deadline = results.getLong("presidential_deadline");
        boolean deadlinePresent = !results.wasNull();
        long referendum = results.getLong("referendum_election_id");
        boolean referendumPresent = !results.wasNull();
        long enacted = results.getLong("enacted_at");
        boolean enactedPresent = !results.wasNull();
        return new LegislativeBill(
                results.getLong("id"), results.getString("bill_number"),
                BillType.valueOf(results.getString("bill_type")), results.getString("title"),
                results.getString("body"), UUID.fromString(results.getString("author_uuid")),
                results.getString("author_name"), BillStatus.valueOf(results.getString("status")),
                Instant.ofEpochMilli(results.getLong("created_at")),
                Instant.ofEpochMilli(results.getLong("updated_at")),
                deadlinePresent ? Instant.ofEpochMilli(deadline) : null,
                results.getString("veto_reason"), referendumPresent ? referendum : null,
                enactedPresent ? Instant.ofEpochMilli(enacted) : null);
    }

    private static List<LegislativeAmendment> amendments(
            Connection connection, long billId) throws SQLException {
        String sql = """
                SELECT a.*, p.last_name AS author_name FROM legislative_amendments a
                JOIN players p ON p.uuid = a.author_uuid
                WHERE a.bill_id = ? ORDER BY a.id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, billId);
            try (ResultSet results = statement.executeQuery()) {
                List<LegislativeAmendment> amendments = new ArrayList<>();
                while (results.next()) {
                    amendments.add(new LegislativeAmendment(
                            results.getLong("id"), UUID.fromString(results.getString("author_uuid")),
                            results.getString("author_name"), results.getString("amendment_text"),
                            results.getString("status"),
                            Instant.ofEpochMilli(results.getLong("created_at"))));
                }
                return List.copyOf(amendments);
            }
        }
    }

    private static List<LegislativeVoteResult> voteResults(
            Connection connection, long billId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM legislative_vote_results WHERE bill_id = ? ORDER BY tallied_at, stage
                """)) {
            statement.setLong(1, billId);
            try (ResultSet results = statement.executeQuery()) {
                List<LegislativeVoteResult> votes = new ArrayList<>();
                while (results.next()) {
                    votes.add(new LegislativeVoteResult(
                            BillStatus.valueOf(results.getString("stage")),
                            results.getInt("yes_votes"), results.getInt("no_votes"),
                            results.getInt("abstain_votes"), results.getInt("quorum_required"),
                            VoteThreshold.valueOf(results.getString("threshold")),
                            results.getInt("passed") == 1,
                            Instant.ofEpochMilli(results.getLong("tallied_at"))));
                }
                return List.copyOf(votes);
            }
        }
    }

    private static List<LegislativeEvent> events(Connection connection, long billId) throws SQLException {
        String sql = """
                SELECT e.*, p.last_name AS actor_name FROM legislative_events e
                LEFT JOIN players p ON p.uuid = e.actor_uuid
                WHERE e.bill_id = ? ORDER BY e.created_at, e.id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, billId);
            try (ResultSet results = statement.executeQuery()) {
                List<LegislativeEvent> events = new ArrayList<>();
                while (results.next()) {
                    events.add(new LegislativeEvent(
                            results.getLong("id"), results.getString("actor_name"),
                            results.getString("event_type"), results.getString("detail"),
                            Instant.ofEpochMilli(results.getLong("created_at"))));
                }
                return List.copyOf(events);
            }
        }
    }

    private static EnactedLaw readLaw(ResultSet results) throws SQLException {
        return new EnactedLaw(
                results.getLong("id"), results.getLong("bill_id"), results.getString("law_number"),
                results.getString("title"), results.getString("body"),
                BillType.valueOf(results.getString("law_type")),
                Instant.ofEpochMilli(results.getLong("enacted_at")));
    }

    private static void transition(
            Connection connection, long billId, BillStatus status, long now, Long deadline) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE legislative_bills SET status = ?, updated_at = ?,
                    presidential_deadline = ? WHERE id = ?
                """)) {
            statement.setString(1, status.name());
            statement.setLong(2, now);
            if (deadline == null) statement.setNull(3, java.sql.Types.BIGINT);
            else statement.setLong(3, deadline);
            statement.setLong(4, billId);
            statement.executeUpdate();
        }
    }

    private static void event(
            Connection connection,
            long billId,
            UUID actor,
            String type,
            String detail,
            long now
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO legislative_events(bill_id, actor_uuid, event_type, detail, created_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, billId);
            if (actor == null) statement.setNull(2, java.sql.Types.VARCHAR);
            else statement.setString(2, actor.toString());
            statement.setString(3, type);
            if (detail == null) statement.setNull(4, java.sql.Types.VARCHAR);
            else statement.setString(4, detail);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    private static LegislativeOperation rollback(
            Connection connection, LegislativeActionResult result) throws SQLException {
        connection.rollback();
        return LegislativeOperation.result(result);
    }

    private static String billNumber(BillType type, long id, long now) {
        int year = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC).getYear();
        String prefix = type == BillType.CONSTITUTIONAL ? "CA" : type == BillType.RESOLUTION ? "R" : "B";
        return "%s-%d-%04d".formatted(prefix, year, id);
    }

    private record VoteCounts(int yes, int no, int abstain) {
    }
}
