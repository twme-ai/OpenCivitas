package dev.opencivitas.court;

import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CourtRepositoryTest {
    private static final long NOW = 2_000_000_000_000L;
    private static final UUID PLAINTIFF = id(1);
    private static final UUID DEFENDANT = id(2);
    private static final UUID COUNSEL = id(3);
    private static final UUID PROSECUTOR = id(4);
    private static final UUID MAGISTRATE = id(5);
    private static final UUID JUDGE = id(6);
    private static final UUID JUSTICE_ONE = id(7);
    private static final UUID JUSTICE_TWO = id(8);
    private static final UUID JUSTICE_THREE = id(9);
    private static final UUID JUSTICE_FOUR = id(10);

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private CourtRepository courts;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = CourtRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        CitizenRepository citizens = new CitizenRepository(database);
        int number = 1;
        for (UUID player : List.of(
                PLAINTIFF, DEFENDANT, COUNSEL, PROSECUTOR, MAGISTRATE,
                JUDGE, JUSTICE_ONE, JUSTICE_TWO, JUSTICE_THREE, JUSTICE_FOUR)) {
            citizens.register(player, "Player" + number++, "en_US", 0);
        }
        job(PROSECUTOR, "prosecutor");
        job(MAGISTRATE, "magistrate");
        job(JUDGE, "judge");
        job(JUSTICE_ONE, "chief-justice");
        job(JUSTICE_TWO, "justice");
        job(JUSTICE_THREE, "justice");
        job(JUSTICE_FOUR, "justice");
        courts = new CourtRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void civilAmountSelectsDistrictOrFederalJurisdiction() throws Exception {
        CourtCase district = courts.fileCivil(
                PLAINTIFF, DEFENDANT, "Small claim", "Property damage", 12_000_000, NOW)
                .courtCase().orElseThrow();
        CourtCase federal = courts.fileCivil(
                PLAINTIFF, DEFENDANT, "Large claim", "Major loss", 12_000_001, NOW + 1)
                .courtCase().orElseThrow();

        assertEquals(CourtLevel.DISTRICT, district.level());
        assertEquals(CourtLevel.FEDERAL, federal.level());
        assertEquals("CV-2033-0001", district.number());
    }

    @Test
    void partiesCanAppointCounselAndSubmitMetadataSafeEvidence() throws Exception {
        CourtCase courtCase = courts.fileCivil(
                PLAINTIFF, DEFENDANT, "Evidence case", "Damaged item", 100_000, NOW)
                .courtCase().orElseThrow();
        assertEquals(CourtActionResult.SUCCESS,
                courts.appointCounsel(courtCase.id(), PLAINTIFF, COUNSEL, NOW + 1).result());
        byte[] item = {1, 2, 3, 4};
        assertEquals(CourtActionResult.SUCCESS,
                courts.submitEvidence(courtCase.id(), COUNSEL, "Signed item", item, NOW + 2).result());
        item[0] = 9;

        CourtCaseDetails details = courts.details(courtCase.id()).orElseThrow();
        assertEquals("Player3", details.counsel().get("PLAINTIFF_COUNSEL"));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, details.evidence().getFirst().itemData());
        assertEquals(CourtActionResult.NOT_AUTHORIZED,
                courts.submitEvidence(courtCase.id(), JUSTICE_ONE, "Not assigned", null, NOW + 3).result());
    }

    @Test
    void magistrateDecidesCivilCaseWithinClaimedDamages() throws Exception {
        CourtCase courtCase = courts.fileCivil(
                PLAINTIFF, DEFENDANT, "Contract", "Unpaid work", 500_000, NOW)
                .courtCase().orElseThrow();
        assertEquals(CourtActionResult.SUCCESS,
                courts.claimBench(courtCase.id(), MAGISTRATE, NOW + 1).result());
        assertEquals(CourtActionResult.INVALID_SANCTION,
                courts.verdict(courtCase.id(), MAGISTRATE, CourtOutcome.LIABLE,
                        500_001, 0, 0, "Too much", NOW + 2).result());

        CourtOperation verdict = courts.verdict(
                courtCase.id(), MAGISTRATE, CourtOutcome.LIABLE,
                400_000, 0, 0, "The contract was proved.", NOW + 3);

        assertEquals(CourtCaseStatus.DECIDED, verdict.courtCase().orElseThrow().status());
        assertEquals(400_000, verdict.courtCase().orElseThrow().judgmentCents());
    }

    @Test
    void criminalFilingAndDistrictSentenceRequireProperAuthority() throws Exception {
        assertEquals(CourtActionResult.NOT_AUTHORIZED,
                courts.fileCriminal(PLAINTIFF, DEFENDANT, false,
                        "Charge", "Unlawful entry", NOW).result());
        CourtCase courtCase = courts.fileCriminal(
                PROSECUTOR, DEFENDANT, false, "Minor charge", "Unlawful entry", NOW)
                .courtCase().orElseThrow();
        courts.claimBench(courtCase.id(), MAGISTRATE, NOW + 1);
        assertEquals(CourtActionResult.INVALID_JURISDICTION,
                courts.verdict(courtCase.id(), MAGISTRATE, CourtOutcome.GUILTY,
                        0, 1_000_001, 60, "Beyond district fine", NOW + 2).result());

        courts.verdict(courtCase.id(), MAGISTRATE, CourtOutcome.GUILTY,
                0, 1_000_000, 60, "Charge proved", NOW + 3);

        CriminalRecord record = courts.criminalRecords(DEFENDANT).getFirst();
        assertEquals(courtCase.id(), record.caseId());
        assertEquals(60, record.jailMinutes());
    }

    @Test
    void federalJudgeCanIssueTimeLimitedWarrant() throws Exception {
        CourtCase courtCase = courts.fileConstitutional(
                PLAINTIFF, DEFENDANT, "Review", "Unlawful search", NOW)
                .courtCase().orElseThrow();
        courts.claimBench(courtCase.id(), JUDGE, NOW + 1);

        assertEquals(CourtActionResult.SUCCESS,
                courts.issueWarrant(courtCase.id(), JUDGE, DEFENDANT,
                        WarrantType.SEARCH, "Specific evidence", 24, NOW + 2).result());
        assertEquals(1, courts.activeWarrants(DEFENDANT, NOW + 3).size());
        assertTrue(courts.activeWarrants(
                DEFENDANT, NOW + 2 + Duration.ofHours(24).toMillis()).isEmpty());
    }

    @Test
    void appealLinksCasesAndSupremeDecisionNeedsTwoMatchingVotes() throws Exception {
        CourtCase original = courts.fileConstitutional(
                PLAINTIFF, DEFENDANT, "Constitutional review", "Invalid action", NOW)
                .courtCase().orElseThrow();
        courts.claimBench(original.id(), JUDGE, NOW + 1);
        courts.verdict(original.id(), JUDGE, CourtOutcome.GRANTED,
                0, 0, 0, "Action invalid", NOW + 2);

        CourtCase appeal = courts.appeal(
                original.id(), DEFENDANT, AppealGround.LAW_ERROR, "Wrong legal test", NOW + 3)
                .courtCase().orElseThrow();
        assertEquals(CourtLevel.SUPREME, appeal.level());
        assertEquals(original.id(), appeal.parentCaseId());
        assertEquals(CourtCaseStatus.APPEALED,
                courts.details(original.id()).orElseThrow().courtCase().status());
        courts.claimBench(appeal.id(), JUSTICE_ONE, NOW + 4);
        courts.claimBench(appeal.id(), JUSTICE_TWO, NOW + 5);
        courts.claimBench(appeal.id(), JUSTICE_THREE, NOW + 6);
        assertEquals(CourtActionResult.PANEL_FULL,
                courts.claimBench(appeal.id(), JUSTICE_FOUR, NOW + 7).result());

        CourtOperation first = courts.verdict(
                appeal.id(), JUSTICE_ONE, CourtOutcome.REVERSED,
                0, 0, 0, "Wrong legal test", NOW + 8);
        assertEquals(CourtCaseStatus.ASSIGNED, first.courtCase().orElseThrow().status());
        CourtOperation second = courts.verdict(
                appeal.id(), JUSTICE_TWO, CourtOutcome.REVERSED,
                0, 0, 0, "Separate opinion", NOW + 9);
        assertEquals(CourtCaseStatus.DECIDED, second.courtCase().orElseThrow().status());
        CourtCase rehearing = courts.appeal(
                appeal.id(), PLAINTIFF, AppealGround.LAW_ERROR,
                "The Supreme Court applied the wrong legal principle", NOW + 10)
                .courtCase().orElseThrow();
        assertEquals(CourtLevel.SUPREME, rehearing.level());
        assertEquals(appeal.id(), rehearing.parentCaseId());
    }

    private void job(UUID player, String job) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO citizen_jobs(player_uuid, job_id, category, joined_at)
                     VALUES (?, ?, 'GOVERNMENT', ?)
                     """)) {
            statement.setString(1, player.toString());
            statement.setString(2, job);
            statement.setLong(3, NOW - 1);
            statement.executeUpdate();
        }
    }

    private static UUID id(long suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
