package dev.opencivitas.job;

import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.economy.LedgerEntryType;
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

class JobEarningRepositoryTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000013");
    private static final JobDefinition MINER = new JobDefinition(
            "miner", JobCategory.TRADE, "miner", true);
    private static final long STARTING_BALANCE = 120_000;

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private CitizenRepository citizens;
    private JobEarningRepository earnings;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = JobEarningRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        citizens = new CitizenRepository(database);
        citizens.register(PLAYER, "Miner", "en_US", STARTING_BALANCE);
        citizens.register(ADMIN, "Administrator", "en_US", STARTING_BALANCE);
        JobRepository jobs = new JobRepository(database);
        jobs.grantQualification(PLAYER, "miner", ADMIN);
        assertEquals(JobJoinResult.SUCCESS, jobs.join(PLAYER, MINER, 2));
        earnings = new JobEarningRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void earningsRemainPendingUntilOneIdempotentLedgerSettlement() throws Exception {
        JobEarningAccrual first = earnings.accrueBreak(
                PLAYER, position(1), List.of(candidate(300)), 1_000, 2_000);
        JobEarningAccrual second = earnings.accrue(
                PLAYER, List.of(candidate(500)), 1_100, 2_000);

        assertEquals(300, first.amountCents());
        assertEquals(500, second.amountCents());
        assertEquals(STARTING_BALANCE, citizens.find(PLAYER).orElseThrow().balanceCents());
        assertTrue(earnings.settleDue(1_999).isEmpty());

        JobPayout payout = earnings.settleDue(2_000).getFirst();
        assertEquals(800, payout.amountCents());
        assertEquals(2, payout.actionCount());
        assertEquals(STARTING_BALANCE + 800, payout.balanceCents());
        assertTrue(earnings.settleDue(2_000).isEmpty());
        assertEquals(1, citizens.transactions(PLAYER, 20, 0).stream()
                .filter(entry -> entry.type() == LedgerEntryType.JOB_EARNING).count());
    }

    @Test
    void placedAndPistonMovedBlocksCannotProduceEarnings() throws Exception {
        JobBlockPosition placed = position(10);
        earnings.markPlacedBlock(placed, "DIAMOND_ORE", PLAYER, 1_000);
        assertEquals(List.of(placed), earnings.placedBlocks());

        JobEarningAccrual blocked = earnings.accrueBreak(
                PLAYER, placed, List.of(candidate(3_000)), 1_100, 2_000);
        assertTrue(blocked.blockedPlacedBlock());
        assertEquals(0, blocked.amountCents());

        JobBlockPosition source = position(20);
        JobBlockPosition destination = position(21);
        earnings.moveBlocks(List.of(new JobBlockMove(source, destination, "DIAMOND_ORE")), 1_200);
        assertTrue(earnings.accrueBreak(
                PLAYER, destination, List.of(candidate(3_000)), 1_300, 2_000).blockedPlacedBlock());

        JobEarningAccrual memoryBlocked = earnings.accrueBreak(
                PLAYER, position(30), List.of(candidate(3_000)), 1_350, 2_000, true);
        assertTrue(memoryBlocked.blockedPlacedBlock());

        JobEarningAccrual natural = earnings.accrueBreak(
                PLAYER, destination, List.of(candidate(3_000)), 1_400, 2_000);
        assertFalse(natural.blockedPlacedBlock());
        assertEquals(3_000, natural.amountCents());
    }

    @Test
    void currentJobMembershipIsRecheckedInsideTheAccrualTransaction() throws Exception {
        JobEarningCandidate unknownJob = new JobEarningCandidate(
                "farmer", JobActionType.BREAK, "DIAMOND_ORE", 3_000);

        JobEarningAccrual accrual = earnings.accrue(
                PLAYER, List.of(unknownJob), 1_000, 2_000);

        assertEquals(0, accrual.amountCents());
        assertEquals(0, accrual.actionCount());
        assertTrue(earnings.settleDue(2_000).isEmpty());
    }

    private static JobEarningCandidate candidate(long amount) {
        return new JobEarningCandidate(
                "miner", JobActionType.BREAK, "DIAMOND_ORE", amount);
    }

    private static JobBlockPosition position(int x) {
        return new JobBlockPosition("world", x, 64, 0);
    }
}
