package dev.opencivitas.mobcapture;

import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.economy.LedgerEntryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobCaptureRepositoryTest {
    private static final UUID ALICE = id(1);
    private static final UUID BOB = id(2);
    private static final UUID TARGET = id(100);
    private static final long NOW = 2_000_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private CitizenRepository citizens;
    private MobCaptureRepository captures;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = MobCaptureRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        citizens = new CitizenRepository(database);
        citizens.register(ALICE, "Alice", "en_US", 10_000);
        citizens.register(BOB, "Bob", "en_US", 10_000);
        addJob(ALICE, "hunter");
        captures = new MobCaptureRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void authorizationChecksJobAndChanceBeforeDebitingAndAuditsSuccess() throws Exception {
        MobCaptureAuthorization ineligible = authorize(BOB, TARGET, true, 5_000);
        assertEquals(MobCaptureResult.NOT_QUALIFIED, ineligible.result());
        assertEquals(10_000, balance(BOB));

        MobCaptureAuthorization escaped = authorize(ALICE, TARGET, false, 5_000);
        assertEquals(MobCaptureResult.CHANCE_FAILED, escaped.result());
        assertEquals(10_000, balance(ALICE));
        assertTrue(captures.logs(null, 10, 0).isEmpty());

        MobCaptureAuthorization captured = authorize(ALICE, TARGET, true, 5_000);
        assertEquals(MobCaptureResult.SUCCESS, captured.result());
        assertEquals(5_000, captured.balanceCents());
        assertEquals("hunter", captured.jobId());
        assertTrue(captures.complete(captured.auditId(), NOW + 1));

        List<MobCaptureRecord> logs = captures.logs(ALICE, 10, 0);
        assertEquals(1, logs.size());
        assertEquals("SUCCESS", logs.getFirst().status());
        assertEquals(TARGET, logs.getFirst().targetId());
        assertEquals(LedgerEntryType.MOB_CAPTURE_FEE,
                citizens.transactions(ALICE, 10, 0).getFirst().type());

        assertEquals(MobCaptureResult.DUPLICATE_TARGET,
                authorize(ALICE, TARGET, true, 1_000).result());
        assertEquals(MobCaptureResult.INSUFFICIENT_FUNDS,
                authorize(ALICE, id(101), true, 6_000).result());
        assertEquals(5_000, balance(ALICE));
    }

    @Test
    void refundsPermitRetryAndStartupRecoveryRestoresEveryPendingFee() throws Exception {
        MobCaptureAuthorization first = authorize(ALICE, TARGET, true, 5_000);
        assertEquals(5_000, balance(ALICE));
        assertTrue(captures.refund(first.auditId(), "target-unavailable", NOW + 1));
        assertEquals(10_000, balance(ALICE));

        MobCaptureAuthorization retry = authorize(ALICE, TARGET, true, 5_000);
        assertEquals(MobCaptureResult.SUCCESS, retry.result());
        assertEquals(5_000, balance(ALICE));
        assertEquals(1, captures.recoverPending(NOW + 2));
        assertEquals(10_000, balance(ALICE));
        assertEquals(0, captures.recoverPending(NOW + 3));

        List<MobCaptureRecord> logs = captures.logs(ALICE, 10, 0);
        assertEquals(2, logs.size());
        assertTrue(logs.stream().allMatch(record -> record.status().equals("REFUNDED")));
        List<LedgerEntryType> entries = citizens.transactions(ALICE, 10, 0).stream()
                .map(entry -> entry.type()).toList();
        assertEquals(2, entries.stream().filter(type -> type == LedgerEntryType.MOB_CAPTURE_FEE).count());
        assertEquals(2, entries.stream().filter(type -> type == LedgerEntryType.MOB_CAPTURE_REFUND).count());
    }

    private MobCaptureAuthorization authorize(UUID actor, UUID target, boolean success, long fee)
            throws Exception {
        return captures.authorize(
                actor, actor.equals(ALICE) ? "Alice" : "Bob", target, "COW", Set.of("hunter"),
                "world", 1.5, 64, 2.5, fee, success, NOW);
    }

    private long balance(UUID playerId) throws Exception {
        return citizens.find(playerId).orElseThrow().balanceCents();
    }

    private void addJob(UUID playerId, String job) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO citizen_jobs(player_uuid, job_id, category, joined_at)
                     VALUES (?, ?, 'TRADE', ?)
                     """)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, job);
            statement.setLong(3, NOW);
            statement.executeUpdate();
        }
    }

    private static UUID id(long suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
