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
    private static final UUID WORKER = UUID.fromString("00000000-0000-0000-0000-000000000032");
    private static final long NOW = 1_000_000L;

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
        citizens.register(WORKER, "Worker", "en_US", 120_000);
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

    @Test
    void offerLifecycleIsPersistentAndAwardsConfiguredRoleAndWage() throws Exception {
        createBusiness();

        assertEquals(BusinessResult.SUCCESS, businesses.offer(
                OWNER, "acme", CUSTOMER, BusinessRole.MANAGER, 5_000, NOW, NOW + 10_000));
        assertEquals(BusinessResult.OFFER_EXISTS, businesses.offer(
                OWNER, "acme", CUSTOMER, BusinessRole.EMPLOYEE, 0, NOW, NOW + 10_000));

        BusinessOffer offer = businesses.offers(CUSTOMER, NOW).getFirst();
        assertEquals("acme", offer.businessSlug());
        assertEquals(BusinessRole.MANAGER, offer.role());
        assertEquals(5_000, offer.wageCents());

        assertEquals(BusinessResult.SUCCESS, businesses.acceptOffer(CUSTOMER, "acme", NOW + 1));
        assertTrue(businesses.offers(CUSTOMER, NOW + 1).isEmpty());
        BusinessMember member = businesses.members("acme").stream()
                .filter(candidate -> candidate.playerId().equals(CUSTOMER))
                .findFirst().orElseThrow();
        assertEquals(BusinessRole.MANAGER, member.role());
        assertEquals(5_000, member.wageCents());
        assertEquals(BusinessResult.ALREADY_MEMBER, businesses.offer(
                OWNER, "acme", CUSTOMER, BusinessRole.EMPLOYEE, 0, NOW, NOW + 10_000));
    }

    @Test
    void expiredAndDeniedOffersCannotBeAccepted() throws Exception {
        createBusiness();
        businesses.offer(OWNER, "acme", CUSTOMER, BusinessRole.EMPLOYEE, 0, NOW, NOW + 10);
        assertEquals(BusinessResult.OFFER_NOT_FOUND,
                businesses.acceptOffer(CUSTOMER, "acme", NOW + 10));

        businesses.offer(OWNER, "acme", CUSTOMER, BusinessRole.EMPLOYEE, 0, NOW, NOW + 10);
        assertEquals(BusinessResult.OFFER_NOT_FOUND,
                businesses.denyOffer(CUSTOMER, "acme", NOW + 10));

        businesses.offer(OWNER, "acme", WORKER, BusinessRole.EMPLOYEE, 0, NOW, NOW + 10_000);
        assertEquals(BusinessResult.SUCCESS, businesses.denyOffer(WORKER, "acme", NOW + 1));
        assertEquals(BusinessResult.OFFER_NOT_FOUND, businesses.denyOffer(WORKER, "acme", NOW + 1));
    }

    @Test
    void hierarchyRestrictsPromotionAndDismissal() throws Exception {
        createBusiness();
        employ(CUSTOMER, BusinessRole.CO_PROPRIETOR, 0);
        employ(WORKER, BusinessRole.MANAGER, 0);

        assertEquals(BusinessResult.NO_PERMISSION,
                businesses.setRole(CUSTOMER, "acme", CUSTOMER, BusinessRole.MANAGER));
        assertEquals(BusinessResult.SUCCESS,
                businesses.setRole(CUSTOMER, "acme", WORKER, BusinessRole.SUPERVISOR));
        assertEquals(BusinessRole.SUPERVISOR, businesses.role("acme", WORKER).orElseThrow());
        assertEquals(BusinessResult.CANNOT_REMOVE_PROPRIETOR,
                businesses.fire(CUSTOMER, "acme", OWNER));
        assertEquals(BusinessResult.SUCCESS, businesses.fire(CUSTOMER, "acme", WORKER));
        assertEquals(BusinessResult.NOT_MEMBER, businesses.fire(CUSTOMER, "acme", WORKER));
    }

    @Test
    void employeesMayResignButProprietorMustTransferOrDisband() throws Exception {
        createBusiness();
        employ(CUSTOMER, BusinessRole.EMPLOYEE, 0);

        assertEquals(BusinessResult.CANNOT_REMOVE_PROPRIETOR, businesses.resign(OWNER, "acme"));
        assertEquals(BusinessResult.SUCCESS, businesses.resign(CUSTOMER, "acme"));
        assertEquals(BusinessResult.NOT_MEMBER, businesses.resign(CUSTOMER, "acme"));
    }

    @Test
    void proprietorshipTransferUpdatesCanonicalOwnerAndRoles() throws Exception {
        createBusiness();
        employ(CUSTOMER, BusinessRole.EMPLOYEE, 0);

        assertEquals(BusinessResult.SUCCESS,
                businesses.transferProprietorship(OWNER, "acme", CUSTOMER));

        Business business = businesses.find("acme").orElseThrow();
        assertEquals(CUSTOMER, business.proprietorId());
        assertEquals(BusinessRole.PROPRIETOR, businesses.role("acme", CUSTOMER).orElseThrow());
        assertEquals(BusinessRole.CO_PROPRIETOR, businesses.role("acme", OWNER).orElseThrow());
        assertEquals(BusinessResult.NO_PERMISSION,
                businesses.transferProprietorship(OWNER, "acme", WORKER));
    }

    @Test
    void payrollPaysAllConfiguredWagesAtomically() throws Exception {
        createBusiness();
        employ(CUSTOMER, BusinessRole.EMPLOYEE, 10_000);
        employ(WORKER, BusinessRole.MANAGER, 20_000);
        businesses.deposit(OWNER, "acme", 40_000);

        PayrollOperation payroll = businesses.runPayroll(OWNER, "acme");

        assertEquals(BusinessResult.SUCCESS, payroll.result());
        assertEquals(2, payroll.recipients());
        assertEquals(30_000, payroll.totalPaidCents());
        assertEquals(10_000, payroll.businessBalanceCents());
        assertEquals(130_000, citizens.find(CUSTOMER).orElseThrow().balanceCents());
        assertEquals(140_000, citizens.find(WORKER).orElseThrow().balanceCents());
        assertEquals(3, businesses.ledger("acme", 10, 0).size());

        PayrollOperation rejected = businesses.runPayroll(OWNER, "acme");
        assertEquals(BusinessResult.INSUFFICIENT_BUSINESS_FUNDS, rejected.result());
        assertEquals(130_000, citizens.find(CUSTOMER).orElseThrow().balanceCents());
        assertEquals(140_000, citizens.find(WORKER).orElseThrow().balanceCents());
        assertEquals(3, businesses.ledger("acme", 10, 0).size());
    }

    @Test
    void staffManagerCanSetWageOnlyBelowOwnAuthority() throws Exception {
        createBusiness();
        employ(CUSTOMER, BusinessRole.CO_PROPRIETOR, 0);
        employ(WORKER, BusinessRole.EMPLOYEE, 0);

        assertEquals(BusinessResult.SUCCESS, businesses.setWage(CUSTOMER, "acme", WORKER, 7_500));
        assertEquals(7_500, businesses.members("acme").stream()
                .filter(member -> member.playerId().equals(WORKER))
                .findFirst().orElseThrow().wageCents());
        assertEquals(BusinessResult.CANNOT_REMOVE_PROPRIETOR,
                businesses.setWage(CUSTOMER, "acme", OWNER, 1));
    }

    private void createBusiness() throws Exception {
        qualifyOwner();
        assertEquals(BusinessResult.SUCCESS, businesses.create(OWNER, "acme", "Acme"));
    }

    private void qualifyOwner() throws Exception {
        new JobRepository(database).grantQualification(OWNER, "entrepreneur", null);
    }

    private void employ(UUID player, BusinessRole role, long wage) throws Exception {
        assertEquals(BusinessResult.SUCCESS,
                businesses.offer(OWNER, "acme", player, role, wage, NOW, NOW + 10_000));
        assertEquals(BusinessResult.SUCCESS, businesses.acceptOffer(player, "acme", NOW + 1));
    }
}
