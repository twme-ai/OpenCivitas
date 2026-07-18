package dev.opencivitas.claim;

import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.economy.LedgerEntryType;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimRepositoryTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000050");
    private static final UUID NEIGHBOR = UUID.fromString("00000000-0000-0000-0000-000000000051");
    private static final UUID TRUSTED = UUID.fromString("00000000-0000-0000-0000-000000000052");
    private static final long NOW = 2_000_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private CitizenRepository citizens;
    private ClaimRepository claims;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = ClaimRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        citizens = new CitizenRepository(database);
        citizens.register(OWNER, "Owner", "en_US", 100_000);
        citizens.register(NEIGHBOR, "Neighbor", "en_US", 100_000);
        citizens.register(TRUSTED, "Trusted", "en_US", 100_000);
        claims = new ClaimRepository(database, 10, 100, 2_000);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void freeBlocksCreateClaimAndOverlapIsRejected() throws Exception {
        ClaimOperation created = claims.create(OWNER, "wilderness", 0, 0, 1, 4, NOW);

        assertEquals(ClaimResult.SUCCESS, created.result());
        LandClaim claim = created.claim().orElseThrow();
        assertEquals(10, claim.area());
        assertEquals(0, created.remainingBlocks());
        assertTrue(claim.contains("wilderness", 1, 3));
        assertFalse(claim.contains("world", 1, 3));

        assertEquals(ClaimResult.OVERLAP,
                claims.create(NEIGHBOR, "wilderness", 1, 4, 5, 8, NOW + 1).result());
        assertEquals(ClaimResult.SUCCESS,
                claims.create(NEIGHBOR, "wilderness", 2, 0, 3, 4, NOW + 1).result());
        assertEquals(2, claims.loadAll().size());
    }

    @Test
    void blockPurchaseIsAtomicAndAudited() throws Exception {
        ClaimOperation purchase = claims.purchaseBlocks(OWNER, 20, NOW);

        assertEquals(ClaimResult.SUCCESS, purchase.result());
        assertEquals(30, purchase.remainingBlocks());
        assertEquals(60_000, purchase.balanceCents());
        assertEquals(60_000, citizens.find(OWNER).orElseThrow().balanceCents());
        assertEquals(LedgerEntryType.CLAIM_BLOCK_PURCHASE,
                citizens.transactions(OWNER, 10, 0).getFirst().type());
        assertEquals(new ClaimCapacity(20, 0, 30, 100), claims.capacity(OWNER));

        assertEquals(ClaimResult.INSUFFICIENT_FUNDS,
                claims.purchaseBlocks(OWNER, 31, NOW + 1).result());
        assertEquals(20, claims.capacity(OWNER).purchasedBlocks());
        assertEquals(60_000, citizens.find(OWNER).orElseThrow().balanceCents());
        assertEquals(ClaimResult.MAX_BLOCKS,
                claims.purchaseBlocks(OWNER, 71, NOW + 1).result());
    }

    @Test
    void resizeChecksEntitlementAndOtherClaims() throws Exception {
        LandClaim first = claims.create(OWNER, "wilderness", 0, 0, 1, 4, NOW)
                .claim().orElseThrow();
        claims.purchaseBlocks(OWNER, 20, NOW + 1);
        claims.create(NEIGHBOR, "wilderness", 5, 0, 6, 4, NOW + 1);

        ClaimOperation resized = claims.resize(OWNER, first.id(), 0, 0, 4, 4, NOW + 2);
        assertEquals(ClaimResult.SUCCESS, resized.result());
        assertEquals(25, resized.claim().orElseThrow().area());
        assertEquals(5, resized.remainingBlocks());

        assertEquals(ClaimResult.OVERLAP,
                claims.resize(OWNER, first.id(), 0, 0, 5, 4, NOW + 3).result());
        assertEquals(ClaimResult.INSUFFICIENT_BLOCKS,
                claims.resize(OWNER, first.id(), 0, 0, 4, 6, NOW + 3).result());
        assertEquals(25, claims.loadAll().stream()
                .filter(claim -> claim.id() == first.id()).findFirst().orElseThrow().area());
    }

    @Test
    void trustTransferAndDeleteMaintainCanonicalOwnership() throws Exception {
        LandClaim claim = claims.create(OWNER, "wilderness", 0, 0, 1, 4, NOW)
                .claim().orElseThrow();

        ClaimOperation trusted = claims.trust(OWNER, claim.id(), TRUSTED, NOW + 1);
        assertEquals(ClaimResult.SUCCESS, trusted.result());
        assertTrue(trusted.claim().orElseThrow().canBuild(TRUSTED));
        assertEquals(ClaimResult.ALREADY_TRUSTED,
                claims.trust(OWNER, claim.id(), TRUSTED, NOW + 2).result());

        ClaimOperation transferred = claims.transfer(OWNER, claim.id(), NEIGHBOR, NOW + 3);
        assertEquals(ClaimResult.SUCCESS, transferred.result());
        assertEquals(NEIGHBOR, transferred.claim().orElseThrow().ownerId());
        assertFalse(transferred.claim().orElseThrow().canBuild(TRUSTED));
        assertEquals(ClaimResult.NO_PERMISSION, claims.delete(OWNER, claim.id()).result());
        assertEquals(ClaimResult.SUCCESS, claims.delete(NEIGHBOR, claim.id()).result());
        assertTrue(claims.loadAll().isEmpty());
    }

    @Test
    void explosionsAndUntrustRequireOwner() throws Exception {
        LandClaim claim = claims.create(OWNER, "wilderness", 0, 0, 1, 4, NOW)
                .claim().orElseThrow();
        claims.trust(OWNER, claim.id(), TRUSTED, NOW + 1);

        assertEquals(ClaimResult.NO_PERMISSION,
                claims.toggleExplosions(TRUSTED, claim.id(), NOW + 2).result());
        ClaimOperation toggled = claims.toggleExplosions(OWNER, claim.id(), NOW + 2);
        assertTrue(toggled.claim().orElseThrow().explosions());
        assertEquals(ClaimResult.SUCCESS, claims.untrust(OWNER, claim.id(), TRUSTED).result());
        assertEquals(ClaimResult.NOT_TRUSTED, claims.untrust(OWNER, claim.id(), TRUSTED).result());
    }

    @Test
    void wandCanOnlyBeIssuedOncePerUtcDay() throws Exception {
        assertTrue(claims.issueWand(OWNER, "2033-05-18"));
        assertFalse(claims.issueWand(OWNER, "2033-05-18"));
        assertTrue(claims.issueWand(OWNER, "2033-05-19"));
    }
}
