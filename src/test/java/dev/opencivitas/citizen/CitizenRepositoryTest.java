package dev.opencivitas.citizen;

import dev.opencivitas.database.Database;
import dev.opencivitas.economy.AccountRegistration;
import dev.opencivitas.economy.LedgerEntry;
import dev.opencivitas.economy.LedgerEntryType;
import dev.opencivitas.economy.TransferResult;
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

class CitizenRepositoryTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private CitizenRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = CitizenRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        repository = new CitizenRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void registrationAwardsStartingBalanceExactlyOnce() throws Exception {
        AccountRegistration first = repository.register(ALICE, "Alice", "en_US", 120_000);
        AccountRegistration second = repository.register(ALICE, "AliceNew", "zh_TW", 120_000);

        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(120_000, second.balanceCents());
        assertEquals("AliceNew", repository.find(ALICE).orElseThrow().lastName());

        List<LedgerEntry> ledger = repository.transactions(ALICE, 10, 0);
        assertEquals(1, ledger.size());
        assertEquals(LedgerEntryType.STARTING_BALANCE, ledger.getFirst().type());
        assertEquals(120_000, ledger.getFirst().amountCents());
    }

    @Test
    void transferCreatesSymmetricLedgerEntries() throws Exception {
        registerBoth();

        TransferResult result = repository.transfer(ALICE, BOB, 25_050);

        assertEquals(TransferResult.Status.SUCCESS, result.status());
        assertEquals(94_950, repository.find(ALICE).orElseThrow().balanceCents());
        assertEquals(145_050, repository.find(BOB).orElseThrow().balanceCents());

        LedgerEntry alicePayment = repository.transactions(ALICE, 10, 0).getFirst();
        LedgerEntry bobPayment = repository.transactions(BOB, 10, 0).getFirst();
        assertEquals(-25_050, alicePayment.amountCents());
        assertEquals("Bob", alicePayment.counterpartyName());
        assertEquals(25_050, bobPayment.amountCents());
        assertEquals("Alice", bobPayment.counterpartyName());
    }

    @Test
    void insufficientFundsRollBackBothAccountsAndLedger() throws Exception {
        registerBoth();

        TransferResult result = repository.transfer(ALICE, BOB, 120_001);

        assertEquals(TransferResult.Status.INSUFFICIENT_FUNDS, result.status());
        assertEquals(120_000, repository.find(ALICE).orElseThrow().balanceCents());
        assertEquals(120_000, repository.find(BOB).orElseThrow().balanceCents());
        assertEquals(1, repository.transactions(ALICE, 10, 0).size());
        assertEquals(1, repository.transactions(BOB, 10, 0).size());
    }

    @Test
    void missingRecipientRollsBackWithdrawal() throws Exception {
        repository.register(ALICE, "Alice", "en_US", 120_000);

        TransferResult result = repository.transfer(ALICE, BOB, 10_000);

        assertEquals(TransferResult.Status.ACCOUNT_NOT_FOUND, result.status());
        assertEquals(120_000, repository.find(ALICE).orElseThrow().balanceCents());
        assertEquals(1, repository.transactions(ALICE, 10, 0).size());
    }

    @Test
    void localePreferenceCanBeSetAndReset() throws Exception {
        repository.register(ALICE, "Alice", "en_US", 120_000);

        repository.setPreferredLocale(ALICE, "zh_TW");
        assertEquals("zh_TW", repository.find(ALICE).orElseThrow().preferredLocale());

        repository.setPreferredLocale(ALICE, null);
        assertEquals(null, repository.find(ALICE).orElseThrow().preferredLocale());
    }

    @Test
    void balanceTopUsesStableOrderingAndPaging() throws Exception {
        registerBoth();
        repository.transfer(ALICE, BOB, 10_000);

        assertEquals("Bob", repository.balanceTop(1, 0).getFirst().playerName());
        assertEquals("Alice", repository.balanceTop(1, 1).getFirst().playerName());
        assertTrue(repository.balanceTop(10, 2).isEmpty());
    }

    @Test
    void activitySessionsUseHeartbeatsToAvoidCountingOfflineCrashTime() throws Exception {
        repository.register(ALICE, "Alice", "en_US", 0);
        repository.startActivitySession(ALICE, 1_000);
        repository.heartbeatActivity(ALICE, 2_000);
        repository.startActivitySession(ALICE, 10_000);
        repository.endActivitySession(ALICE, 12_000);

        CitizenActivity activity = repository.activity(ALICE, 1_500);

        assertEquals(3_000, activity.total().toMillis());
        assertEquals(2_500, activity.recent().toMillis());
    }

    private void registerBoth() throws Exception {
        repository.register(ALICE, "Alice", "en_US", 120_000);
        repository.register(BOB, "Bob", "en_US", 120_000);
    }
}
