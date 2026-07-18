package dev.opencivitas.police;

import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.court.CourtCase;
import dev.opencivitas.court.CourtRepository;
import dev.opencivitas.court.WarrantType;
import dev.opencivitas.database.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoliceRepositoryTest {
    private static final long NOW = 2_000_000_000_000L;
    private static final UUID ATTACKER = id(1);
    private static final UUID VICTIM = id(2);
    private static final UUID OFFICER = id(3);
    private static final UUID OTHER_OFFICER = id(4);
    private static final UUID JUDGE = id(5);

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private PoliceRepository police;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = PoliceRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        CitizenRepository citizens = new CitizenRepository(database);
        citizens.register(ATTACKER, "Attacker", "en_US", 120_000);
        citizens.register(VICTIM, "Victim", "en_US", 120_000);
        citizens.register(OFFICER, "Officer", "en_US", 120_000);
        citizens.register(OTHER_OFFICER, "OtherOfficer", "en_US", 120_000);
        citizens.register(JUDGE, "Judge", "en_US", 120_000);
        job(OFFICER, "police-officer");
        job(OTHER_OFFICER, "detective");
        job(JUDGE, "judge");
        police = new PoliceRepository(database, Duration.ofMinutes(5), Duration.ofMinutes(5));
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void consentAndSelfDefenseAreSnapshottedPerAttack() throws Exception {
        AttackResult aggression = police.recordAttack(
                ATTACKER, VICTIM, "world", 1, 2, 3, 4_000,
                "ENTITY_ATTACK", new byte[]{1, 2}, NOW).value().orElseThrow();
        assertEquals(LegalBasis.UNLAWFUL, aggression.incident().legalBasis());
        assertTrue(aggression.fightStarted());

        AttackResult defense = police.recordAttack(
                VICTIM, ATTACKER, "world", 1, 2, 3, 2_000,
                "ENTITY_ATTACK", null, NOW + 1).value().orElseThrow();
        assertEquals(LegalBasis.SELF_DEFENSE, defense.incident().legalBasis());
        assertFalse(defense.fightStarted());

        assertTrue(police.toggleConsent(VICTIM, NOW + 2));
        AttackResult consented = police.recordAttack(
                ATTACKER, VICTIM, "world", 1, 2, 3, 1_000,
                "PROJECTILE", null, NOW + Duration.ofMinutes(6).toMillis())
                .value().orElseThrow();
        assertEquals(LegalBasis.CONSENT, consented.incident().legalBasis());
    }

    @Test
    void unlawfulDeathCreatesOneClueAndOneEmergencyReport() throws Exception {
        police.recordAttack(ATTACKER, VICTIM, "world", 10, 64, 20,
                20_000, "ENTITY_ATTACK", null, NOW);
        DeathResult death = police.recordDeath(
                ATTACKER, VICTIM, "world", 10, 64, 20, NOW + 1).value().orElseThrow();

        assertTrue(death.incident().fatal());
        assertTrue(death.clue().isPresent());
        PoliceReport report = police.fileEmergencyReport(VICTIM, NOW + 2).value().orElseThrow();
        assertEquals(LawResult.ALREADY_REPORTED,
                police.fileEmergencyReport(VICTIM, NOW + 3).result());
        PoliceReportDetails details = police.reportDetails(report.id(), VICTIM).value().orElseThrow();
        assertEquals("Attacker", details.report().suspectName());
        assertEquals(death.clue().orElseThrow().id(), details.clue().orElseThrow().id());
        assertEquals(LawResult.NOT_AUTHORIZED, police.reportDetails(report.id(), JUDGE).result());
        assertEquals(LawResult.NOT_AUTHORIZED,
                police.reports(JUDGE, 10, 0, null).result());
        assertEquals(1, police.reports(OFFICER, 10, 0, ReportStatus.OPEN)
                .value().orElseThrow().size());
    }

    @Test
    void forensicClueCanOnlyBeCollectedOnceByLawEnforcement() throws Exception {
        police.recordAttack(ATTACKER, VICTIM, "world", 0, 64, 0,
                20_000, "ENTITY_ATTACK", null, NOW);
        long clueId = police.recordDeath(ATTACKER, VICTIM, "world", 0, 64, 0, NOW + 1)
                .value().orElseThrow().clue().orElseThrow().id();

        assertEquals(LawResult.NOT_AUTHORIZED, police.collectClue(clueId, ATTACKER, NOW + 2).result());
        assertEquals(LawResult.SUCCESS, police.collectClue(clueId, OFFICER, NOW + 3).result());
        assertEquals(LawResult.CLUE_UNAVAILABLE,
                police.collectClue(clueId, OTHER_OFFICER, NOW + 4).result());
    }

    @Test
    void reportChargeArrestAndReleaseConserveFineAndRecordConviction() throws Exception {
        PoliceReport report = reportMurder();
        assertEquals(LawResult.SUCCESS, police.claimReport(report.id(), OFFICER, NOW + 3).result());
        assertEquals(LawResult.ASSIGNED_TO_OTHER,
                police.dismissReport(report.id(), OTHER_OFFICER, "No basis", NOW + 4).result());
        Offense murder = new Offense("murder", 50_000, 10);
        PoliceCharge charge = police.chargeReport(
                report.id(), OFFICER, murder, "Collected forensic clue", NOW + 5)
                .value().orElseThrow();

        PoliceArrest arrest = police.arrest(OFFICER, ATTACKER, 10, 120, NOW + 6)
                .value().orElseThrow();
        assertEquals(50_000, arrest.fineCollectedCents());
        assertEquals(10, arrest.jailMinutes());
        assertEquals(ArrestStatus.ACTIVE, arrest.status());
        assertEquals(70_000, balance(ATTACKER));
        assertEquals(-50_000, latestLedgerAmount(ATTACKER));
        assertTrue(police.wanted(ATTACKER).isEmpty());
        assertEquals("PC-" + charge.id(), police.criminalRecords(ATTACKER).getFirst().reference());

        assertTrue(police.releaseDue(NOW + 6 + Duration.ofMinutes(9).toMillis()).isEmpty());
        assertEquals(ArrestStatus.RELEASED,
                police.releaseDue(NOW + 6 + Duration.ofMinutes(10).toMillis()).getFirst().status());
        assertTrue(police.activeDetentions().isEmpty());
    }

    @Test
    void courtArrestWarrantAuthorizesCustodyAndIsExecutedAtomically() throws Exception {
        CourtRepository courts = new CourtRepository(database);
        CourtCase courtCase = courts.fileConstitutional(
                VICTIM, ATTACKER, "Warrant review", "Probable cause", NOW)
                .courtCase().orElseThrow();
        courts.claimBench(courtCase.id(), JUDGE, NOW + 1);
        courts.issueWarrant(courtCase.id(), JUDGE, ATTACKER,
                WarrantType.ARREST, "Appear before the court", 24, NOW + 2);

        PoliceArrest arrest = police.arrest(OFFICER, ATTACKER, 10, 120, NOW + 3)
                .value().orElseThrow();
        assertEquals(10, arrest.jailMinutes());
        assertTrue(arrest.chargeIds().isEmpty());
        assertEquals(1, arrest.warrantIds().size());
        assertTrue(courts.activeWarrants(ATTACKER, NOW + 4).isEmpty());
        assertEquals(LawResult.ALREADY_DETAINED,
                police.arrest(OFFICER, ATTACKER, 10, 120, NOW + 5).result());
    }

    @Test
    void voidedSummaryChargeRemainsAuditableButIsMarkedCleared() throws Exception {
        PoliceCharge charge = police.chargeCitizen(
                ATTACKER, OFFICER, new Offense("trespass", 20_000, 2),
                "Alarm and witness statement", NOW).value().orElseThrow();
        police.arrest(OFFICER, ATTACKER, 10, 120, NOW + 1);
        police.voidCharge(charge.id(), OFFICER, "Evidence disproved the charge", NOW + 2);

        PublicCriminalRecord record = police.criminalRecords(ATTACKER).getFirst();
        assertEquals("PC-" + charge.id(), record.reference());
        assertTrue(record.cleared());
    }

    @Test
    void voidedUnservedAllegationDoesNotBecomePublicCriminalRecord() throws Exception {
        PoliceCharge charge = police.chargeCitizen(
                ATTACKER, OFFICER, new Offense("trespass", 20_000, 2),
                "Mistaken identity", NOW).value().orElseThrow();
        police.voidCharge(charge.id(), OFFICER, "Different citizen identified", NOW + 1);

        assertTrue(police.criminalRecords(ATTACKER).isEmpty());
    }

    private PoliceReport reportMurder() throws Exception {
        police.recordAttack(ATTACKER, VICTIM, "world", 0, 64, 0,
                20_000, "ENTITY_ATTACK", null, NOW);
        police.recordDeath(ATTACKER, VICTIM, "world", 0, 64, 0, NOW + 1);
        return police.fileEmergencyReport(VICTIM, NOW + 2).value().orElseThrow();
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

    private long balance(UUID player) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT balance_cents FROM accounts WHERE player_uuid = ?")) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                assertTrue(results.next());
                return results.getLong(1);
            }
        }
    }

    private long latestLedgerAmount(UUID player) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT amount_cents FROM ledger_entries WHERE player_uuid = ?
                     ORDER BY created_at DESC, id DESC LIMIT 1
                     """)) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                assertTrue(results.next());
                return results.getLong(1);
            }
        }
    }

    private static UUID id(long suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
