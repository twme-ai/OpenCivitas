package dev.opencivitas.legislature;

import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.election.ElectionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegislatureRepositoryTest {
    private static final long NOW = 2_000_000_000_000L;
    private static final List<UUID> HOUSE = ids(10, 6);
    private static final List<UUID> SENATE = ids(20, 3);
    private static final UUID PRESIDENT = id(30);

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private LegislatureRepository legislature;
    private ElectionRepository elections;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = LegislatureRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        CitizenRepository citizens = new CitizenRepository(database);
        int number = 1;
        for (UUID player : allPlayers()) {
            citizens.register(player, "Citizen" + number++, "en_US", 0);
        }
        int seat = 1;
        for (UUID player : HOUSE) term("house", seat++, player);
        seat = 1;
        for (UUID player : SENATE) term("senate-a", seat++, player);
        term("president", 1, PRESIDENT);
        legislature = new LegislatureRepository(database, Duration.ofDays(14));
        elections = new ElectionRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void regularBillPassesBothChambersAndReceivesAssent() throws Exception {
        LegislativeBill bill = introduce(BillType.REGULAR, "Public Parks Act");
        passHouse(bill.id(), BillStatus.HOUSE_VOTING, false);
        assertEquals(BillStatus.SENATE_VOTING, bill(bill.id()).status());
        passSenate(bill.id(), BillStatus.SENATE_VOTING);
        assertEquals(BillStatus.PRESIDENT_REVIEW, bill(bill.id()).status());

        LegislativeOperation assent = legislature.presidentialAction(
                bill.id(), PRESIDENT, true, null, NOW + 40);

        assertEquals(BillStatus.ENACTED, assent.bill().orElseThrow().status());
        assertEquals("Public Parks Act", legislature.laws(10, 0).getFirst().title());
        assertEquals(2, legislature.details(bill.id()).orElseThrow().voteResults().size());
    }

    @Test
    void senateAmendmentRequiresHouseReconsideration() throws Exception {
        LegislativeBill bill = introduce(BillType.REGULAR, "Transit Act");
        passHouse(bill.id(), BillStatus.HOUSE_VOTING, false);
        assertEquals(LegislativeActionResult.SUCCESS,
                legislature.amend(bill.id(), SENATE.getFirst(), "Add a sunset review.", NOW + 20).result());
        passSenate(bill.id(), BillStatus.SENATE_VOTING);
        assertEquals(BillStatus.HOUSE_RECONSIDERATION, bill(bill.id()).status());

        passHouse(bill.id(), BillStatus.HOUSE_RECONSIDERATION, false);
        legislature.presidentialAction(bill.id(), PRESIDENT, true, null, NOW + 50);

        assertTrue(legislature.laws(10, 0).getFirst().body().contains("Add a sunset review."));
    }

    @Test
    void vetoOverrideRequiresBothChambers() throws Exception {
        LegislativeBill bill = introduce(BillType.REGULAR, "Road Safety Act");
        passHouse(bill.id(), BillStatus.HOUSE_VOTING, false);
        passSenate(bill.id(), BillStatus.SENATE_VOTING);
        legislature.presidentialAction(bill.id(), PRESIDENT, false, "Too broad", NOW + 40);

        assertEquals(BillStatus.HOUSE_OVERRIDE,
                legislature.startOverride(bill.id(), HOUSE.getFirst(), NOW + 41)
                        .bill().orElseThrow().status());
        passHouse(bill.id(), BillStatus.HOUSE_OVERRIDE, false);
        assertEquals(BillStatus.SENATE_OVERRIDE, bill(bill.id()).status());
        passSenate(bill.id(), BillStatus.SENATE_OVERRIDE);

        assertEquals(BillStatus.ENACTED, bill(bill.id()).status());
    }

    @Test
    void appropriationVetoCannotBeOverridden() throws Exception {
        LegislativeBill bill = introduce(BillType.APPROPRIATION, "Annual Appropriation");
        passHouse(bill.id(), BillStatus.HOUSE_VOTING, false);
        passSenate(bill.id(), BillStatus.SENATE_VOTING);
        legislature.presidentialAction(bill.id(), PRESIDENT, false, "Unfunded", NOW + 40);

        assertEquals(LegislativeActionResult.APPROPRIATION_OVERRIDE_FORBIDDEN,
                legislature.startOverride(bill.id(), HOUSE.getFirst(), NOW + 41).result());
        assertEquals(BillStatus.VETOED, bill(bill.id()).status());
    }

    @Test
    void presidentialInactionAssumesAssentAfterFourteenDays() throws Exception {
        LegislativeBill bill = introduce(BillType.REGULAR, "Archives Act");
        passHouse(bill.id(), BillStatus.HOUSE_VOTING, false);
        passSenate(bill.id(), BillStatus.SENATE_VOTING);
        long deadline = bill(bill.id()).presidentialDeadline().toEpochMilli();

        assertTrue(legislature.autoAssent(deadline - 1).isEmpty());
        assertEquals(1, legislature.autoAssent(deadline).size());
        assertEquals(BillStatus.ENACTED, bill(bill.id()).status());
    }

    @Test
    void latePresidentialVetoCannotRaceAssumedAssent() throws Exception {
        LegislativeBill bill = introduce(BillType.REGULAR, "Libraries Act");
        passHouse(bill.id(), BillStatus.HOUSE_VOTING, false);
        passSenate(bill.id(), BillStatus.SENATE_VOTING);
        long deadline = bill(bill.id()).presidentialDeadline().toEpochMilli();

        LegislativeOperation late = legislature.presidentialAction(
                bill.id(), PRESIDENT, false, "Too late", deadline);

        assertEquals(LegislativeActionResult.PRESIDENTIAL_WINDOW_EXPIRED, late.result());
        assertEquals(BillStatus.ENACTED, bill(bill.id()).status());
    }

    @Test
    void constitutionalBillNeedsSupermajoritiesAssentAndReferendum() throws Exception {
        LegislativeBill bill = introduce(
                BillType.CONSTITUTIONAL, "A Bill to Amend the Constitution - Local Government");
        passHouse(bill.id(), BillStatus.HOUSE_VOTING, true);
        passSenate(bill.id(), BillStatus.SENATE_VOTING);
        legislature.presidentialAction(bill.id(), PRESIDENT, true, null, NOW + 40);
        assertEquals(BillStatus.REFERENDUM_REQUIRED, bill(bill.id()).status());

        LegislatureService service = new LegislatureService(
                legislature, elections, Duration.ofHours(48));
        service.settle(NOW + 41);
        LegislativeBill linked = bill(bill.id());
        assertEquals(BillStatus.REFERENDUM, linked.status());
        long electionId = linked.referendumElectionId();
        elections.castBallot(electionId, HOUSE.get(0), List.of("yes"), NOW + 42);
        elections.castBallot(electionId, HOUSE.get(1), List.of("yes"), NOW + 42);
        elections.castBallot(electionId, HOUSE.get(2), List.of("no"), NOW + 42);

        service.settle(NOW + 41 + Duration.ofHours(48).toMillis());

        assertEquals(BillStatus.ENACTED, bill(bill.id()).status());
        assertEquals(BillType.CONSTITUTIONAL, legislature.laws(10, 0).getFirst().type());
    }

    @Test
    void resolutionNeedsOnlyAHouseVoteAndDoesNotEnterLawCodex() throws Exception {
        LegislativeBill bill = introduce(BillType.RESOLUTION, "Public Thanks Resolution");
        passHouse(bill.id(), BillStatus.HOUSE_VOTING, false);

        assertEquals(BillStatus.ENACTED, bill(bill.id()).status());
        assertTrue(legislature.laws(10, 0).isEmpty());
    }

    private LegislativeBill introduce(BillType type, String title) throws Exception {
        LegislativeBill draft = legislature.createDraft(
                HOUSE.getFirst(), type, title, "Section 1. Original civic text.", NOW + 1)
                .bill().orElseThrow();
        return legislature.submit(draft.id(), HOUSE.getFirst(), NOW + 2).bill().orElseThrow();
    }

    private void passHouse(long billId, BillStatus stage, boolean constitutional) throws Exception {
        for (int index = 0; index < 4; index++) {
            legislature.vote(billId, HOUSE.get(index), LegislativeVote.YES, NOW + 10 + index);
        }
        if (constitutional || stage == BillStatus.HOUSE_OVERRIDE) {
            legislature.vote(billId, HOUSE.get(4), LegislativeVote.NO, NOW + 15);
            legislature.vote(billId, HOUSE.get(5),
                    stage == BillStatus.HOUSE_OVERRIDE ? LegislativeVote.ABSTAIN : LegislativeVote.NO,
                    NOW + 16);
        } else {
            legislature.vote(billId, HOUSE.get(4), LegislativeVote.ABSTAIN, NOW + 15);
            legislature.vote(billId, HOUSE.get(5), LegislativeVote.ABSTAIN, NOW + 16);
        }
        LegislativeOperation result = legislature.tally(billId, HOUSE.getFirst(), NOW + 19);
        assertEquals(stage, result.voteResult().orElseThrow().stage());
        assertTrue(result.voteResult().orElseThrow().passed());
    }

    private void passSenate(long billId, BillStatus stage) throws Exception {
        legislature.vote(billId, SENATE.get(0), LegislativeVote.YES, NOW + 21);
        legislature.vote(billId, SENATE.get(1), LegislativeVote.YES, NOW + 22);
        legislature.vote(billId, SENATE.get(2), LegislativeVote.NO, NOW + 23);
        LegislativeOperation result = legislature.tally(billId, SENATE.getFirst(), NOW + 29);
        assertEquals(stage, result.voteResult().orElseThrow().stage());
        assertTrue(result.voteResult().orElseThrow().passed());
    }

    private LegislativeBill bill(long id) throws Exception {
        return legislature.details(id).orElseThrow().bill();
    }

    private void term(String office, int seat, UUID holder) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO office_terms(
                         office_id, seat_number, holder_uuid, started_at, ends_at)
                     VALUES (?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, office);
            statement.setInt(2, seat);
            statement.setString(3, holder.toString());
            statement.setLong(4, NOW - 1);
            statement.setLong(5, NOW + Duration.ofDays(365).toMillis());
            statement.executeUpdate();
        }
    }

    private static List<UUID> allPlayers() {
        List<UUID> players = new ArrayList<>(HOUSE);
        players.addAll(SENATE);
        players.add(PRESIDENT);
        return players;
    }

    private static List<UUID> ids(int start, int count) {
        List<UUID> ids = new ArrayList<>();
        for (int index = 0; index < count; index++) ids.add(id(start + index));
        return List.copyOf(ids);
    }

    private static UUID id(long suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
