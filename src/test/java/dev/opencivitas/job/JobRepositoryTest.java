package dev.opencivitas.job;

import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobRepositoryTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final JobDefinition MINER = job("miner", JobCategory.TRADE, true);
    private static final JobDefinition FARMER = job("farmer", JobCategory.TRADE, true);
    private static final JobDefinition FISHER = job("fisher", JobCategory.TRADE, true);
    private static final JobDefinition REALTOR = job("realtor", JobCategory.PROFESSION, true);
    private static final JobDefinition MASON = job("mason", JobCategory.PROFESSION, true);
    private static final JobDefinition DOCTOR = job("doctor", JobCategory.GOVERNMENT, false);

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private JobRepository jobs;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = JobRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        CitizenRepository citizens = new CitizenRepository(database);
        citizens.register(PLAYER, "Citizen", "en_US", 120_000);
        citizens.register(ADMIN, "Administrator", "en_US", 120_000);
        jobs = new JobRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void selfJoinRequiresQualification() throws Exception {
        assertEquals(JobJoinResult.MISSING_QUALIFICATION, jobs.join(PLAYER, MINER, 2));

        assertTrue(jobs.grantQualification(PLAYER, "miner", ADMIN));
        assertFalse(jobs.grantQualification(PLAYER, "miner", ADMIN));
        assertEquals(JobJoinResult.SUCCESS, jobs.join(PLAYER, MINER, 2));
        assertEquals(JobJoinResult.ALREADY_JOINED, jobs.join(PLAYER, MINER, 2));
        assertEquals(List.of("miner"), jobs.qualifications(PLAYER));
    }

    @Test
    void enforcesTwoTradeLimitTransactionally() throws Exception {
        qualify(MINER, FARMER, FISHER);
        assertEquals(JobJoinResult.SUCCESS, jobs.join(PLAYER, MINER, 2));
        assertEquals(JobJoinResult.SUCCESS, jobs.join(PLAYER, FARMER, 2));
        assertEquals(JobJoinResult.CATEGORY_LIMIT, jobs.join(PLAYER, FISHER, 2));

        assertEquals(List.of("farmer", "miner"), jobs.jobs(PLAYER).stream().map(CitizenJob::id).sorted().toList());
    }

    @Test
    void enforcesOneProfessionLimit() throws Exception {
        qualify(REALTOR, MASON);
        assertEquals(JobJoinResult.SUCCESS, jobs.join(PLAYER, REALTOR, 1));
        assertEquals(JobJoinResult.CATEGORY_LIMIT, jobs.join(PLAYER, MASON, 1));
    }

    @Test
    void appointmentBypassesQualificationForGovernmentRoles() throws Exception {
        assertEquals(JobJoinResult.SUCCESS, jobs.appoint(PLAYER, DOCTOR, Integer.MAX_VALUE, ADMIN));
        CitizenJob role = jobs.jobs(PLAYER).getFirst();
        assertEquals("doctor", role.id());
        assertEquals(ADMIN.toString(), role.appointedBy());
    }

    @Test
    void leavingJobsDoesNotEraseQualifications() throws Exception {
        qualify(MINER, REALTOR);
        jobs.join(PLAYER, MINER, 2);
        jobs.join(PLAYER, REALTOR, 1);

        assertTrue(jobs.leave(PLAYER, "miner"));
        assertFalse(jobs.leave(PLAYER, "miner"));
        assertEquals(1, jobs.leaveCategory(PLAYER, JobCategory.PROFESSION));
        assertEquals(0, jobs.jobs(PLAYER).size());
        assertEquals(List.of("miner", "realtor"), jobs.qualifications(PLAYER));
    }

    @Test
    void qualificationCanBeRevoked() throws Exception {
        jobs.grantQualification(PLAYER, "miner", ADMIN);
        assertTrue(jobs.revokeQualification(PLAYER, "miner"));
        assertFalse(jobs.revokeQualification(PLAYER, "miner"));
        assertTrue(jobs.qualifications(PLAYER).isEmpty());
    }

    private void qualify(JobDefinition... definitions) throws Exception {
        for (JobDefinition definition : definitions) {
            jobs.grantQualification(PLAYER, definition.qualification(), ADMIN);
        }
    }

    private static JobDefinition job(String id, JobCategory category, boolean selfJoin) {
        return new JobDefinition(id, category, id, selfJoin);
    }
}
