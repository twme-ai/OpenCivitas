package dev.opencivitas.exam;

import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.job.JobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExamRepositoryTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000020");

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private ExamRepository exams;
    private JobRepository jobs;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = ExamRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        new CitizenRepository(database).register(PLAYER, "Student", "en_US", 120_000);
        exams = new ExamRepository(database);
        jobs = new JobRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void failedAttemptIsAuditedWithoutQualification() throws Exception {
        ExamRecordResult result = exams.record(PLAYER, "miner", "miner", 1, 3, false);

        assertFalse(result.qualificationGranted());
        assertTrue(jobs.qualifications(PLAYER).isEmpty());
        ExamAttempt attempt = exams.attempts(PLAYER).getFirst();
        assertFalse(attempt.passed());
        assertEquals(1, attempt.score());
        assertEquals(3, attempt.total());
    }

    @Test
    void passingAwardsQualificationExactlyOnceWhileAuditingRetries() throws Exception {
        ExamRecordResult first = exams.record(PLAYER, "miner", "miner", 3, 3, true);
        ExamRecordResult retry = exams.record(PLAYER, "miner", "miner", 3, 3, true);

        assertTrue(first.qualificationGranted());
        assertFalse(retry.qualificationGranted());
        assertEquals(java.util.List.of("miner"), jobs.qualifications(PLAYER));
        assertEquals(2, exams.attempts(PLAYER).size());
    }

    @Test
    void rejectsImpossibleScoresBeforeWriting() {
        assertThrows(IllegalArgumentException.class,
                () -> exams.record(PLAYER, "miner", "miner", 4, 3, true));
    }
}
