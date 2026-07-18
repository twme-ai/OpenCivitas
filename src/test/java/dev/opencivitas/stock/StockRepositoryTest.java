package dev.opencivitas.stock;

import dev.opencivitas.business.BusinessRepository;
import dev.opencivitas.business.BusinessResult;
import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.job.JobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockRepositoryTest {
    private static final UUID ISSUER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OPERATOR = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final long STARTING_BALANCE = 1_000_000;

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private CitizenRepository citizens;
    private BusinessRepository businesses;
    private StockRepository stocks;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = StockRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        citizens = new CitizenRepository(database);
        citizens.register(ISSUER, "Issuer", "en_US", STARTING_BALANCE);
        citizens.register(OPERATOR, "Operator", "en_US", STARTING_BALANCE);
        citizens.register(ALICE, "Alice", "en_US", STARTING_BALANCE);
        citizens.register(BOB, "Bob", "en_US", STARTING_BALANCE);
        JobRepository jobs = new JobRepository(database);
        jobs.grantQualification(ISSUER, "entrepreneur", null);
        jobs.grantQualification(OPERATOR, "entrepreneur", null);
        businesses = new BusinessRepository(database);
        assertEquals(BusinessResult.SUCCESS, businesses.create(ISSUER, "issuer", "Issuer Incorporated"));
        assertEquals(BusinessResult.SUCCESS, businesses.create(OPERATOR, "exchange", "Civic Exchange"));
        stocks = new StockRepository(database);
        assertEquals(StockResult.SUCCESS, stocks.createExchange(
                OPERATOR, "exchange", "civex", "Civic Exchange", 100, 1_000, 1_000).result());
        assertEquals(StockResult.SUCCESS, stocks.applyListing(
                ISSUER, "civex", "issuer", "CIV", 10, 1_000, 1_000_000, 2_000).result());
        assertEquals(StockResult.SUCCESS, stocks.reviewListing(
                OPERATOR, "CIV", true, 3_000).result());
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void listingRequiresIssuerAndExchangeConsent() throws Exception {
        StockListing listing = stocks.findListing("civ").orElseThrow();
        StockQuote quote = stocks.quote("CIV").orElseThrow();

        assertEquals(StockListingStatus.ACTIVE, listing.status());
        assertEquals(1_000, quote.bestAskCents());
        assertEquals(0, quote.investorShares());
        assertEquals(10, conservedShares("CIV"));
    }

    @Test
    void primaryPurchaseConservesCashSharesAndFees() throws Exception {
        StockOrderPlacement placement = stocks.placeOrder(
                ALICE, "CIV", StockOrderSide.BUY, 10, 1_000, 20, 1_000_000, 4_000).value();

        assertEquals(StockOrderStatus.FILLED, placement.order().status());
        assertEquals(1, placement.trades().size());
        assertEquals(10, stocks.portfolio(ALICE).getFirst().availableQuantity());
        assertEquals(989_900, balance(ALICE));
        assertEquals(9_900, businesses.find("issuer").orElseThrow().balanceCents());
        assertEquals(200, businesses.find("exchange").orElseThrow().balanceCents());
        assertEquals(10, conservedShares("CIV"));
    }

    @Test
    void priceTimeMatchingUsesRestingCustomPriceAndRefundsImprovement() throws Exception {
        buyFromIssuer(ALICE, 10);
        StockOrderPlacement ask = stocks.placeOrder(
                ALICE, "CIV", StockOrderSide.SELL, 4, 1_500, 20, 1_000_000, 5_000).value();

        StockOrderPlacement bid = stocks.placeOrder(
                BOB, "CIV", StockOrderSide.BUY, 4, 1_600, 20, 1_000_000, 6_000).value();

        assertEquals(StockOrderStatus.OPEN, ask.order().status());
        assertEquals(StockOrderStatus.FILLED, bid.order().status());
        assertEquals(1_500, bid.trades().getFirst().priceCents());
        assertEquals(993_940, balance(BOB));
        assertEquals(995_840, balance(ALICE));
        assertEquals(4, stocks.portfolio(BOB).getFirst().availableQuantity());
        assertEquals(1_500, stocks.quote("CIV").orElseThrow().listing().lastPriceCents());
        assertEquals(10, conservedShares("CIV"));
    }

    @Test
    void cancellationReturnsCashAndShareEscrow() throws Exception {
        buyFromIssuer(ALICE, 5);
        long aliceAfterPurchase = balance(ALICE);
        StockOrder sell = stocks.placeOrder(
                ALICE, "CIV", StockOrderSide.SELL, 2, 2_000, 20, 1_000_000, 5_000).value().order();
        StockOrder buy = stocks.placeOrder(
                BOB, "CIV", StockOrderSide.BUY, 2, 500, 20, 1_000_000, 5_100).value().order();

        assertEquals(3, stocks.portfolio(ALICE).getFirst().availableQuantity());
        stocks.cancelOrder(ALICE, sell.id(), 6_000);
        stocks.cancelOrder(BOB, buy.id(), 6_100);

        assertEquals(5, stocks.portfolio(ALICE).getFirst().availableQuantity());
        assertEquals(aliceAfterPurchase, balance(ALICE));
        assertEquals(STARTING_BALANCE, balance(BOB));
        assertEquals(10, conservedShares("CIV"));
    }

    @Test
    void ownOrdersDoNotSelfTrade() throws Exception {
        buyFromIssuer(ALICE, 10);
        stocks.placeOrder(ALICE, "CIV", StockOrderSide.SELL,
                1, 1_200, 20, 1_000_000, 5_000);

        StockOrderPlacement bid = stocks.placeOrder(ALICE, "CIV", StockOrderSide.BUY,
                1, 1_200, 20, 1_000_000, 5_100).value();

        assertTrue(bid.trades().isEmpty());
        assertEquals(2, stocks.orders(ALICE, true).size());
        assertEquals(10, conservedShares("CIV"));
    }

    @Test
    void dividendsIncludeSharesInSellEscrowAndRemainAtomic() throws Exception {
        buyFromIssuer(ALICE, 10);
        stocks.placeOrder(ALICE, "CIV", StockOrderSide.SELL,
                4, 2_000, 20, 1_000_000, 5_000);
        businesses.deposit(ISSUER, "issuer", 50_000);
        long before = balance(ALICE);

        StockDividend dividend = stocks.payDividend(ISSUER, "CIV", 100, 6_000).value();

        assertEquals(10, dividend.paidShares());
        assertEquals(1_000, dividend.totalCents());
        assertEquals(before + 1_000, balance(ALICE));
        assertEquals(49_000 + 9_900, businesses.find("issuer").orElseThrow().balanceCents());
    }

    @Test
    void haltStopsNewOrdersUntilExchangeOperatorResumesTrading() throws Exception {
        assertEquals(StockResult.SUCCESS,
                stocks.setListingHalted(OPERATOR, "CIV", true, false, 5_000).result());
        assertEquals(StockResult.LISTING_INACTIVE, stocks.placeOrder(
                ALICE, "CIV", StockOrderSide.BUY, 1, 1_000, 20, 1_000_000, 5_100).result());
        assertEquals(StockResult.NO_PERMISSION,
                stocks.setListingHalted(ALICE, "CIV", false, false, 5_200).result());
        assertEquals(StockResult.SUCCESS,
                stocks.setListingHalted(OPERATOR, "CIV", false, false, 5_300).result());
    }

    @Test
    void orderLimitsAndInsufficientEscrowLeaveBalancesAndSharesUnchanged() throws Exception {
        assertEquals(StockResult.INSUFFICIENT_SHARES, stocks.placeOrder(
                BOB, "CIV", StockOrderSide.SELL, 1, 1_000, 20, 1_000_000, 4_000).result());
        assertEquals(StockResult.INSUFFICIENT_FUNDS, stocks.placeOrder(
                BOB, "CIV", StockOrderSide.BUY, 10, 1_000_000, 20, 1_000_000, 4_100).result());
        StockOperation<StockOrderPlacement> first = stocks.placeOrder(
                BOB, "CIV", StockOrderSide.BUY, 1, 500, 1, 1_000_000, 4_200);

        assertEquals(StockResult.SUCCESS, first.result());
        assertEquals(StockResult.OPEN_ORDER_LIMIT, stocks.placeOrder(
                BOB, "CIV", StockOrderSide.BUY, 1, 400, 1, 1_000_000, 4_300).result());
        assertEquals(999_495, balance(BOB));
        assertEquals(10, conservedShares("CIV"));
    }

    @Test
    void exchangeOperatorControlsListingReview() throws Exception {
        assertEquals(StockResult.SUCCESS, stocks.applyListing(
                ISSUER, "civex", "issuer", "ALT", 5, 2_000, 1_000_000, 5_000).result());

        assertEquals(StockResult.NO_PERMISSION,
                stocks.reviewListing(ALICE, "ALT", true, 5_100).result());
        assertEquals(StockResult.SUCCESS,
                stocks.reviewListing(OPERATOR, "ALT", false, 5_200).result());
        assertEquals(StockListingStatus.REJECTED,
                stocks.findListing("ALT").orElseThrow().status());
    }

    private void buyFromIssuer(UUID player, long quantity) throws Exception {
        StockOperation<StockOrderPlacement> operation = stocks.placeOrder(
                player, "CIV", StockOrderSide.BUY, quantity, 1_000, 20, 1_000_000, 4_000);
        assertEquals(StockResult.SUCCESS, operation.result());
    }

    private long balance(UUID player) throws Exception {
        return citizens.find(player).orElseThrow().balanceCents();
    }

    private long conservedShares(String symbol) throws Exception {
        String sql = """
                SELECT listing.treasury_shares
                    + COALESCE((SELECT SUM(quantity) FROM stock_holdings WHERE listing_id = listing.id), 0)
                    + COALESCE((SELECT SUM(remaining_quantity) FROM stock_orders
                        WHERE listing_id = listing.id AND side = 'SELL'
                            AND status IN ('OPEN', 'PARTIAL')), 0) AS shares
                FROM stock_listings listing WHERE listing.symbol = ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getLong(1) : 0;
            }
        }
    }
}
