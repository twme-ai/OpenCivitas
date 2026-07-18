package dev.opencivitas.health;

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
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthRepositoryTest {
    private static final UUID PATIENT = id(1);
    private static final UUID DOCTOR = id(2);
    private static final UUID OTHER_DOCTOR = id(3);
    private static final long NOW = 2_000_000_000_000L;

    private static final HealthConditionDefinition COLD = new HealthConditionDefinition(
            "common-cold", Map.of("en_US", "Common Cold"), List.of("cough"),
            "cough-syrup", CareSetting.HOSPITAL, 3, 0.08, Map.of("cold", 0.15));
    private static final TreatmentDefinition SYRUP = new TreatmentDefinition(
            "cough-syrup", Map.of("en_US", "Cough Syrup"), null,
            List.of(), CareSetting.HOSPITAL, 4_000, 0);
    private static final TreatmentDefinition EAR_DROPS = new TreatmentDefinition(
            "ear-drops", Map.of("en_US", "Ear Drops"), null,
            List.of(), CareSetting.PHARMACY, 0, 2_000);

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private HealthRepository health;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = HealthRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        CitizenRepository citizens = new CitizenRepository(database);
        citizens.register(PATIENT, "Patient", "en_US", 100_000);
        citizens.register(DOCTOR, "Doctor", "en_US", 100_000);
        citizens.register(OTHER_DOCTOR, "OtherDoctor", "en_US", 100_000);
        job(DOCTOR, "doctor");
        job(OTHER_DOCTOR, "medical-specialist");
        health = new HealthRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void temperatureProfileIsCreatedAndUpdated() throws Exception {
        assertEquals(37_000, health.temperature(PATIENT, 37_000, NOW).value().orElseThrow());
        assertEquals(34_000, health.updateTemperature(PATIENT, 34_000, 37_000, NOW + 1)
                .value().orElseThrow());
        assertEquals(34_000, health.temperature(PATIENT, 37_000, NOW + 2).value().orElseThrow());
    }

    @Test
    void oneActiveConditionCanBeTreatedOnlyByADoctor() throws Exception {
        assertEquals(HealthResult.SUCCESS, health.expose(PATIENT, COLD, "cold", NOW).result());
        assertEquals(HealthResult.ALREADY_AFFECTED,
                health.expose(PATIENT, COLD, "contagion", NOW + 1).result());

        assertEquals(HealthResult.NOT_DOCTOR,
                health.treat(PATIENT, PATIENT, SYRUP, List.of(COLD.id()), NOW + 2).result());
        MedicalTreatment treatment = health.treat(
                PATIENT, DOCTOR, SYRUP, List.of(COLD.id()), NOW + 3).value().orElseThrow();

        assertTrue(health.activeConditions(PATIENT).isEmpty());
        assertTrue(treatment.medicareEligible());
        assertEquals(1, health.conditionHistory(PATIENT, 10).size());
        assertEquals(HealthResult.NO_TREATABLE_CONDITION,
                health.treat(PATIENT, DOCTOR, SYRUP, List.of(COLD.id()), NOW + 4).result());
    }

    @Test
    void medicareBenefitCanBeClaimedExactlyOnce() throws Exception {
        health.expose(PATIENT, COLD, "cold", NOW);
        health.treat(PATIENT, DOCTOR, SYRUP, List.of(COLD.id()), NOW + 1);

        MedicareClaim claim = health.bulkBill(DOCTOR, PATIENT, NOW + 2).value().orElseThrow();

        assertEquals(4_000, claim.benefitCents());
        assertEquals(104_000, balance(DOCTOR));
        assertEquals("MEDICARE_BENEFIT", latestLedgerType(DOCTOR));
        assertEquals(HealthResult.NO_MEDICARE_CLAIM,
                health.bulkBill(DOCTOR, PATIENT, NOW + 3).result());
    }

    @Test
    void doctorCannotClaimMedicareForSelfTreatment() throws Exception {
        health.expose(DOCTOR, COLD, "cold", NOW);
        MedicalTreatment treatment = health.treat(
                DOCTOR, DOCTOR, SYRUP, List.of(COLD.id()), NOW + 1).value().orElseThrow();

        assertTrue(!treatment.medicareEligible());
        assertEquals(HealthResult.NO_MEDICARE_CLAIM,
                health.bulkBill(DOCTOR, DOCTOR, NOW + 2).result());
    }

    @Test
    void medicalCallClaimIsExclusiveAndRestartSafe() throws Exception {
        MedicalCall call = health.call(PATIENT, "world", 1, 64, 2, NOW).value().orElseThrow();
        assertEquals(call.id(), health.call(PATIENT, "world", 9, 9, 9, NOW + 1)
                .value().orElseThrow().id());
        assertEquals(DOCTOR, health.claimCall(PATIENT, DOCTOR, NOW + 2)
                .value().orElseThrow().claimedBy());
        assertEquals(HealthResult.CALL_ALREADY_CLAIMED,
                health.claimCall(PATIENT, OTHER_DOCTOR, NOW + 3).result());
        assertEquals(1, health.releaseStaleClaims(NOW + 2));
        assertEquals(OTHER_DOCTOR, health.claimCall(PATIENT, OTHER_DOCTOR, NOW + 4)
                .value().orElseThrow().claimedBy());
        assertTrue(health.markAttended(call.id(), OTHER_DOCTOR, NOW + 5));
        assertTrue(health.openCalls().isEmpty());
        assertTrue(health.call(PATIENT, "world", 3, 65, 4, NOW + 6).value().isPresent());
    }

    @Test
    void callMonitorsArePersistedByBlockPosition() throws Exception {
        BlockPosition position = new BlockPosition("hospital", 1, 70, 2);
        assertTrue(health.setMonitor(position, DOCTOR, NOW));
        assertTrue(health.isMonitor(position));
        assertTrue(health.removeMonitor(position));
        assertTrue(!health.isMonitor(position));
    }

    @Test
    void pharmacyPurchaseRequiresARegisteredCounterAndAuditsCopay() throws Exception {
        BlockPosition counter = new BlockPosition("hospital", 5, 70, 5);
        assertEquals(HealthResult.PHARMACY_NOT_FOUND,
                health.purchaseMedicine(PATIENT, EAR_DROPS, counter, NOW).result());
        assertTrue(health.setPharmacy(counter, DOCTOR, NOW + 1));

        assertEquals(98_000, health.purchaseMedicine(PATIENT, EAR_DROPS, counter, NOW + 2)
                .value().orElseThrow());
        assertEquals(98_000, balance(PATIENT));
        assertEquals("PHARMACY_COPAY", latestLedgerType(PATIENT));
        assertEquals(HealthResult.NOT_PHARMACY_TREATMENT,
                health.purchaseMedicine(PATIENT, SYRUP, counter, NOW + 3).result());
        assertTrue(health.removePharmacy(counter));
    }

    private void job(UUID player, String job) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO citizen_jobs(player_uuid, job_id, category, joined_at, appointed_by)
                     VALUES (?, ?, 'GOVERNMENT', ?, NULL)
                     """)) {
            statement.setString(1, player.toString());
            statement.setString(2, job);
            statement.setLong(3, NOW);
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

    private String latestLedgerType(UUID player) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT entry_type FROM ledger_entries WHERE player_uuid = ? ORDER BY id DESC LIMIT 1
                     """)) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                assertTrue(results.next());
                return results.getString(1);
            }
        }
    }

    private static UUID id(long suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
