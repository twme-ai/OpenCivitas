package dev.opencivitas.auction;

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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionRepositoryTest {
    private static final UUID SELLER = UUID.fromString("00000000-0000-0000-0000-000000000090");
    private static final UUID BIDDER = UUID.fromString("00000000-0000-0000-0000-000000000091");
    private static final UUID RIVAL = UUID.fromString("00000000-0000-0000-0000-000000000092");
    private static final long NOW = 2_000_000_000_000L;
    private static final byte[] ITEM = {1, 2, 3, 4};

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private CitizenRepository citizens;
    private AuctionRepository auctions;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = AuctionRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        citizens = new CitizenRepository(database);
        citizens.register(SELLER, "Seller", "en_US", 100_000);
        citizens.register(BIDDER, "Bidder", "en_US", 100_000);
        citizens.register(RIVAL, "Rival", "en_US", 100_000);
        auctions = new AuctionRepository(database, 500, 2);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void listingPreservesOpaqueItemBytesAndEnforcesLimit() throws Exception {
        AuctionListing first = create(NOW + 10_000);

        assertArrayEquals(ITEM, first.itemData());
        assertEquals("DIAMOND_SWORD", first.itemKey());
        assertEquals(1, auctions.active(10, 0).size());
        auctions.create(SELLER, new byte[]{5}, "STONE", "Stone", 1,
                1_000, null, NOW, NOW + 10_000);
        assertEquals(AuctionResult.LISTING_LIMIT,
                auctions.create(SELLER, new byte[]{6}, "DIRT", "Dirt", 1,
                        1_000, null, NOW, NOW + 10_000).result());
        assertEquals(1, auctions.search("sword", 10).size());
    }

    @Test
    void outbidRefundAndOwnRaiseConserveEscrow() throws Exception {
        AuctionListing listing = create(NOW + 10_000);

        assertEquals(AuctionResult.SUCCESS, auctions.bid(BIDDER, listing.id(), 20_000, NOW + 1).result());
        assertEquals(80_000, citizens.find(BIDDER).orElseThrow().balanceCents());

        assertEquals(AuctionResult.SUCCESS, auctions.bid(RIVAL, listing.id(), 25_000, NOW + 2).result());
        assertEquals(100_000, citizens.find(BIDDER).orElseThrow().balanceCents());
        assertEquals(75_000, citizens.find(RIVAL).orElseThrow().balanceCents());
        assertEquals(LedgerEntryType.AUCTION_REFUND,
                citizens.transactions(BIDDER, 10, 0).getFirst().type());

        AuctionOperation raised = auctions.bid(RIVAL, listing.id(), 30_000, NOW + 3);
        assertEquals(AuctionResult.SUCCESS, raised.result());
        assertEquals(70_000, citizens.find(RIVAL).orElseThrow().balanceCents());
        assertEquals(30_000, raised.listing().orElseThrow().currentBidCents());
    }

    @Test
    void buyoutUsesExistingEscrowAndPaysSellerAtomically() throws Exception {
        AuctionListing listing = create(NOW + 10_000);
        auctions.bid(BIDDER, listing.id(), 20_000, NOW + 1);

        AuctionOperation bought = auctions.buyout(BIDDER, listing.id(), NOW + 2);

        assertEquals(AuctionResult.SUCCESS, bought.result());
        assertEquals(AuctionState.SOLD, bought.listing().orElseThrow().state());
        assertEquals(50_000, citizens.find(BIDDER).orElseThrow().balanceCents());
        assertEquals(150_000, citizens.find(SELLER).orElseThrow().balanceCents());
        AuctionClaim claim = auctions.claims(BIDDER).getFirst();
        assertArrayEquals(ITEM, claim.itemData());
        assertTrue(auctions.claim(BIDDER, claim.id(), NOW + 3).isPresent());
        assertTrue(auctions.claim(BIDDER, claim.id(), NOW + 4).isEmpty());
    }

    @Test
    void buyoutRefundsDifferentHighBidder() throws Exception {
        AuctionListing listing = create(NOW + 10_000);
        auctions.bid(BIDDER, listing.id(), 20_000, NOW + 1);

        assertEquals(AuctionResult.SUCCESS, auctions.buyout(RIVAL, listing.id(), NOW + 2).result());
        assertEquals(100_000, citizens.find(BIDDER).orElseThrow().balanceCents());
        assertEquals(50_000, citizens.find(RIVAL).orElseThrow().balanceCents());
        assertEquals(150_000, citizens.find(SELLER).orElseThrow().balanceCents());
    }

    @Test
    void expirySettlesWinningBidOrReturnsUnsoldItem() throws Exception {
        AuctionListing sold = create(NOW + 10);
        auctions.bid(BIDDER, sold.id(), 20_000, NOW + 1);

        assertEquals(1, auctions.settleExpired(NOW + 10).size());
        assertEquals(AuctionState.SOLD, auctions.find(sold.id()).orElseThrow().state());
        assertEquals(120_000, citizens.find(SELLER).orElseThrow().balanceCents());
        assertEquals(1, auctions.claims(BIDDER).size());

        AuctionListing unsold = auctions.create(
                SELLER, new byte[]{9}, "STONE", "Stone", 1,
                1_000, null, NOW + 20, NOW + 30).listing().orElseThrow();
        auctions.settleExpired(NOW + 30);
        assertEquals(AuctionState.EXPIRED, auctions.find(unsold.id()).orElseThrow().state());
        assertEquals(1, auctions.claims(SELLER).size());
    }

    @Test
    void sellerMayCancelOnlyBeforeFirstBid() throws Exception {
        AuctionListing listing = create(NOW + 10_000);
        assertEquals(AuctionResult.NO_PERMISSION, auctions.cancel(BIDDER, listing.id(), NOW + 1).result());
        auctions.bid(BIDDER, listing.id(), 20_000, NOW + 2);
        assertEquals(AuctionResult.BID_EXISTS, auctions.cancel(SELLER, listing.id(), NOW + 3).result());

        AuctionListing cancellable = auctions.create(
                SELLER, new byte[]{8}, "DIRT", "Dirt", 1,
                1_000, null, NOW, NOW + 10_000).listing().orElseThrow();
        assertEquals(AuctionResult.SUCCESS, auctions.cancel(SELLER, cancellable.id(), NOW + 1).result());
        assertEquals(AuctionState.CANCELLED, auctions.find(cancellable.id()).orElseThrow().state());
        assertEquals(1, auctions.claims(SELLER).size());
    }

    private AuctionListing create(long endsAt) throws Exception {
        return auctions.create(
                SELLER, ITEM, "DIAMOND_SWORD", "Diamond Sword", 1,
                10_000, 50_000L, NOW, endsAt).listing().orElseThrow();
    }
}
