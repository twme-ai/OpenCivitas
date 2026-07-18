package dev.opencivitas.business;

import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.economy.LedgerEntryType;
import dev.opencivitas.job.JobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessRepositoryTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-000000000031");

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private CitizenRepository citizens;
    private BusinessRepository businesses;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = BusinessRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        citizens = new CitizenRepository(database);
        citizens.register(OWNER, "Owner", "en_US", 120_000);
        citizens.register(CUSTOMER, "Customer", "en_US", 120_000);
        businesses = new BusinessRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void creationRequiresEntrepreneurQualificationAndUniqueName() throws Exception {
        assertEquals(BusinessResult.MISSING_QUALIFICATION,
                businesses.create(OWNER, "acme", "Acme"));

        qualifyOwner();
        assertEquals(BusinessResult.SUCCESS, businesses.create(OWNER, "acme", "Acme"));
        assertEquals(BusinessResult.NAME_TAKEN, businesses.create(OWNER, "ACME", "Other"));

        Business business = businesses.find("AcMe").orElseThrow();
        assertEquals(OWNER, business.proprietorId());
        assertEquals(0, business.balanceCents());
        assertEquals(BusinessStatus.ACTIVE, business.status());
        assertEquals(1, businesses.list(OWNER).size());
        assertTrue(businesses.list(CUSTOMER).isEmpty());
    }

    @Test
    void depositAndWithdrawalMoveFundsAtomically() throws Exception {
        createBusiness();

        assertEquals(BusinessResult.SUCCESS, businesses.deposit(OWNER, "acme", 50_000).result());
        assertEquals(70_000, citizens.find(OWNER).orElseThrow().balanceCents());
        assertEquals(50_000, businesses.find("acme").orElseThrow().balanceCents());
        assertEquals(LedgerEntryType.BUSINESS_TRANSFER,
                citizens.transactions(OWNER, 10, 0).getFirst().type());

        assertEquals(BusinessResult.SUCCESS, businesses.withdraw(OWNER, "acme", 20_000).result());
        assertEquals(90_000, citizens.find(OWNER).orElseThrow().balanceCents());
        assertEquals(30_000, businesses.find("acme").orElseThrow().balanceCents());
        assertEquals(2, businesses.ledger("acme", 10, 0).size());
    }

    @Test
    void rejectsUnauthorizedAndUnderfundedTransfersWithoutPartialWrites() throws Exception {
        createBusiness();
        businesses.deposit(OWNER, "acme", 10_000);

        assertEquals(BusinessResult.NO_PERMISSION,
                businesses.withdraw(CUSTOMER, "acme", 1_000).result());
        assertEquals(BusinessResult.INSUFFICIENT_BUSINESS_FUNDS,
                businesses.withdraw(OWNER, "acme", 10_001).result());
        assertEquals(BusinessResult.INSUFFICIENT_PERSONAL_FUNDS,
                businesses.deposit(CUSTOMER, "acme", 120_001).result());

        assertEquals(10_000, businesses.find("acme").orElseThrow().balanceCents());
        assertEquals(120_000, citizens.find(CUSTOMER).orElseThrow().balanceCents());
        assertEquals(1, businesses.ledger("acme", 10, 0).size());
    }

    @Test
    void businessPaymentCreditsCitizenAndBothLedgers() throws Exception {
        createBusiness();
        businesses.deposit(OWNER, "acme", 40_000);

        BusinessOperation payment = businesses.pay(OWNER, "acme", CUSTOMER, 12_500);

        assertEquals(BusinessResult.SUCCESS, payment.result());
        assertEquals(27_500, payment.businessBalanceCents());
        assertEquals(132_500, citizens.find(CUSTOMER).orElseThrow().balanceCents());
        assertEquals(LedgerEntryType.BUSINESS_PAYMENT,
                citizens.transactions(CUSTOMER, 10, 0).getFirst().type());
        assertEquals("PAYMENT", businesses.ledger("acme", 10, 0).getFirst().type());
    }

    @Test
    void disbandRefundsProprietorAndPreservesAuditHistory() throws Exception {
        createBusiness();
        businesses.deposit(OWNER, "acme", 30_000);

        BusinessOperation disband = businesses.disband(OWNER, "acme");

        assertEquals(BusinessResult.SUCCESS, disband.result());
        assertEquals(120_000, citizens.find(OWNER).orElseThrow().balanceCents());
        assertEquals(BusinessStatus.DISBANDED, businesses.find("acme").orElseThrow().status());
        assertEquals(2, businesses.ledger("acme", 10, 0).size());
        assertEquals(BusinessResult.BUSINESS_INACTIVE,
                businesses.deposit(OWNER, "acme", 1_000).result());
    }

    @Test
    void onlyProprietorMayDisband() throws Exception {
        createBusiness();
        assertEquals(BusinessResult.NO_PERMISSION, businesses.disband(CUSTOMER, "acme").result());
        assertEquals(BusinessStatus.ACTIVE, businesses.find("acme").orElseThrow().status());
    }

    private void createBusiness() throws Exception {
        qualifyOwner();
        assertEquals(BusinessResult.SUCCESS, businesses.create(OWNER, "acme", "Acme"));
    }

    private void qualifyOwner() throws Exception {
        new JobRepository(database).grantQualification(OWNER, "entrepreneur", null);
    }
}
