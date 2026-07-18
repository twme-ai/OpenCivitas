package dev.opencivitas.auction;

import dev.opencivitas.database.Database;
import dev.opencivitas.economy.LedgerEntryType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class AuctionRepository {
    private static final String LISTING_SELECT = """
            SELECT listing.*, seller.last_name AS seller_name,
                   bidder.last_name AS bidder_name
            FROM auction_listings listing
            JOIN players seller ON seller.uuid = listing.seller_uuid
            LEFT JOIN players bidder ON bidder.uuid = listing.current_bidder_uuid
            """;

    private final Database database;
    private final long minimumIncrementCents;
    private final int listingLimit;

    public AuctionRepository(Database database, long minimumIncrementCents, int listingLimit) {
        if (minimumIncrementCents < 1 || listingLimit < 1) {
            throw new IllegalArgumentException("Invalid auction settings");
        }
        this.database = database;
        this.minimumIncrementCents = minimumIncrementCents;
        this.listingLimit = listingLimit;
    }

    public AuctionOperation create(
            UUID seller,
            byte[] itemData,
            String itemKey,
            String itemName,
            int itemQuantity,
            long startingBidCents,
            Long buyoutCents,
            long now,
            long endsAt
    ) throws SQLException {
        if (itemData.length == 0 || itemKey.isBlank() || itemName.isBlank() || itemQuantity < 1
                || startingBidCents < 1 || (buyoutCents != null && buyoutCents < startingBidCents)
                || endsAt <= now) {
            throw new IllegalArgumentException("Invalid auction listing");
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!accountExists(connection, seller)) {
                    connection.rollback();
                    return AuctionOperation.failed(AuctionResult.CITIZEN_NOT_FOUND);
                }
                if (activeCount(connection, seller) >= listingLimit) {
                    connection.rollback();
                    return AuctionOperation.failed(AuctionResult.LISTING_LIMIT);
                }
                long id = insertListing(
                        connection, seller, itemData, itemKey, itemName, itemQuantity,
                        startingBidCents, buyoutCents, now, endsAt);
                AuctionListing listing = find(connection, id).orElseThrow();
                connection.commit();
                return AuctionOperation.succeeded(listing, 0, playerBalance(connection, seller));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public Optional<AuctionListing> find(long listingId) throws SQLException {
        try (Connection connection = database.openConnection()) {
            return find(connection, listingId);
        }
    }

    public List<AuctionListing> active(int limit, int offset) throws SQLException {
        String sql = LISTING_SELECT + """
                WHERE listing.state = 'ACTIVE'
                ORDER BY listing.ends_at, listing.id
                LIMIT ? OFFSET ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            statement.setInt(2, offset);
            return readListings(statement);
        }
    }

    public List<AuctionListing> search(String query, int limit) throws SQLException {
        String sql = LISTING_SELECT + """
                WHERE listing.state = 'ACTIVE'
                  AND (listing.item_key LIKE ? ESCAPE '\\' OR listing.item_name LIKE ? ESCAPE '\\')
                ORDER BY listing.ends_at, listing.id
                LIMIT ?
                """;
        String escaped = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, escaped);
            statement.setString(2, escaped);
            statement.setInt(3, limit);
            return readListings(statement);
        }
    }

    public List<AuctionListing> bySeller(UUID seller, int limit) throws SQLException {
        String sql = LISTING_SELECT + """
                WHERE listing.seller_uuid = ?
                ORDER BY listing.created_at DESC, listing.id DESC
                LIMIT ?
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, seller.toString());
            statement.setInt(2, limit);
            return readListings(statement);
        }
    }

    public AuctionOperation bid(UUID bidder, long listingId, long amount, long now) throws SQLException {
        if (amount < 1) {
            throw new IllegalArgumentException("Auction bid must be positive");
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<AuctionListing> selected = find(connection, listingId);
                if (selected.isEmpty()) {
                    connection.rollback();
                    return AuctionOperation.failed(AuctionResult.LISTING_NOT_FOUND);
                }
                AuctionListing listing = selected.get();
                AuctionResult availability = availability(listing, now);
                if (availability != AuctionResult.SUCCESS) {
                    connection.rollback();
                    return AuctionOperation.failed(availability);
                }
                if (listing.sellerId().equals(bidder)) {
                    connection.rollback();
                    return AuctionOperation.failed(AuctionResult.SELF);
                }
                long minimum;
                try {
                    minimum = listing.currentBidCents() == 0
                            ? listing.startingBidCents()
                            : Math.addExact(listing.currentBidCents(), minimumIncrementCents);
                } catch (ArithmeticException exception) {
                    connection.rollback();
                    return AuctionOperation.failed(AuctionResult.INVALID_BID);
                }
                if (amount < minimum || listing.buyoutCents() != null && amount >= listing.buyoutCents()) {
                    connection.rollback();
                    return AuctionOperation.failed(AuctionResult.INVALID_BID);
                }
                if (!accountExists(connection, bidder)) {
                    connection.rollback();
                    return AuctionOperation.failed(AuctionResult.CITIZEN_NOT_FOUND);
                }

                boolean ownBid = bidder.equals(listing.currentBidderId());
                long debit = ownBid ? amount - listing.currentBidCents() : amount;
                if (!debitPlayer(connection, bidder, debit)) {
                    connection.rollback();
                    return AuctionOperation.failed(AuctionResult.INSUFFICIENT_FUNDS);
                }
                if (listing.currentBidderId() != null && !ownBid) {
                    creditPlayer(connection, listing.currentBidderId(), listing.currentBidCents());
                    insertLedger(
                            connection, listing.currentBidderId(), bidder, listing.currentBidCents(),
                            LedgerEntryType.AUCTION_REFUND, now);
                }
                insertLedger(connection, bidder, listing.sellerId(), -debit, LedgerEntryType.AUCTION_BID, now);
                updateBid(connection, listingId, bidder, amount);
                insertBid(connection, listingId, bidder, amount, now);
                AuctionListing updated = find(connection, listingId).orElseThrow();
                long balance = playerBalance(connection, bidder);
                connection.commit();
                return AuctionOperation.succeeded(updated, amount, balance);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public AuctionOperation buyout(UUID buyer, long listingId, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<AuctionListing> selected = find(connection, listingId);
                if (selected.isEmpty()) {
                    connection.rollback();
                    return AuctionOperation.failed(AuctionResult.LISTING_NOT_FOUND);
                }
                AuctionListing listing = selected.get();
                AuctionResult availability = availability(listing, now);
                if (availability != AuctionResult.SUCCESS) {
                    connection.rollback();
                    return AuctionOperation.failed(availability);
                }
                if (listing.buyoutCents() == null) {
                    connection.rollback();
                    return AuctionOperation.failed(AuctionResult.BUYOUT_UNAVAILABLE);
                }
                if (listing.sellerId().equals(buyer)) {
                    connection.rollback();
                    return AuctionOperation.failed(AuctionResult.SELF);
                }
                if (!accountExists(connection, buyer)) {
                    connection.rollback();
                    return AuctionOperation.failed(AuctionResult.CITIZEN_NOT_FOUND);
                }
                long price = listing.buyoutCents();
                boolean ownBid = buyer.equals(listing.currentBidderId());
                long debit = ownBid ? price - listing.currentBidCents() : price;
                if (!debitPlayer(connection, buyer, debit)) {
                    connection.rollback();
                    return AuctionOperation.failed(AuctionResult.INSUFFICIENT_FUNDS);
                }
                if (listing.currentBidderId() != null && !ownBid) {
                    creditPlayer(connection, listing.currentBidderId(), listing.currentBidCents());
                    insertLedger(
                            connection, listing.currentBidderId(), buyer, listing.currentBidCents(),
                            LedgerEntryType.AUCTION_REFUND, now);
                }
                creditPlayer(connection, listing.sellerId(), price);
                insertLedger(connection, buyer, listing.sellerId(), -debit, LedgerEntryType.AUCTION_PURCHASE, now);
                insertLedger(connection, listing.sellerId(), buyer, price, LedgerEntryType.AUCTION_SALE, now);
                settleListing(connection, listing.id(), AuctionState.SOLD, buyer, price, now);
                insertClaim(connection, listing, buyer, now);
                AuctionListing updated = find(connection, listing.id()).orElseThrow();
                long balance = playerBalance(connection, buyer);
                connection.commit();
                return AuctionOperation.succeeded(updated, price, balance);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public AuctionOperation cancel(UUID seller, long listingId, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<AuctionListing> selected = find(connection, listingId);
                if (selected.isEmpty()) {
                    connection.rollback();
                    return AuctionOperation.failed(AuctionResult.LISTING_NOT_FOUND);
                }
                AuctionListing listing = selected.get();
                if (listing.state() != AuctionState.ACTIVE) {
                    connection.rollback();
                    return AuctionOperation.failed(AuctionResult.LISTING_INACTIVE);
                }
                if (!listing.sellerId().equals(seller)) {
                    connection.rollback();
                    return AuctionOperation.failed(AuctionResult.NO_PERMISSION);
                }
                if (listing.currentBidderId() != null) {
                    connection.rollback();
                    return AuctionOperation.failed(AuctionResult.BID_EXISTS);
                }
                settleListing(connection, listing.id(), AuctionState.CANCELLED, null, 0, now);
                insertClaim(connection, listing, seller, now);
                AuctionListing updated = find(connection, listing.id()).orElseThrow();
                connection.commit();
                return AuctionOperation.succeeded(updated, 0, playerBalance(connection, seller));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<AuctionListing> settleExpired(long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                String sql = LISTING_SELECT + """
                        WHERE listing.state = 'ACTIVE' AND listing.ends_at <= ?
                        ORDER BY listing.ends_at, listing.id
                        """;
                List<AuctionListing> expired;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setLong(1, now);
                    expired = readListings(statement);
                }
                for (AuctionListing listing : expired) {
                    if (listing.currentBidderId() == null) {
                        settleListing(connection, listing.id(), AuctionState.EXPIRED, null, 0, now);
                        insertClaim(connection, listing, listing.sellerId(), now);
                    } else {
                        creditPlayer(connection, listing.sellerId(), listing.currentBidCents());
                        insertLedger(
                                connection, listing.sellerId(), listing.currentBidderId(),
                                listing.currentBidCents(), LedgerEntryType.AUCTION_SALE, now);
                        settleListing(
                                connection, listing.id(), AuctionState.SOLD,
                                listing.currentBidderId(), listing.currentBidCents(), now);
                        insertClaim(connection, listing, listing.currentBidderId(), now);
                    }
                }
                connection.commit();
                return List.copyOf(expired);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public List<AuctionClaim> claims(UUID player) throws SQLException {
        String sql = """
                SELECT * FROM auction_claims
                WHERE player_uuid = ? AND claimed_at IS NULL
                ORDER BY created_at, id
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<AuctionClaim> claims = new ArrayList<>();
                while (results.next()) {
                    claims.add(readClaim(results));
                }
                return List.copyOf(claims);
            }
        }
    }

    public Optional<AuctionClaim> claim(UUID player, long claimId, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<AuctionClaim> selected;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT * FROM auction_claims
                        WHERE id = ? AND player_uuid = ? AND claimed_at IS NULL
                        """)) {
                    statement.setLong(1, claimId);
                    statement.setString(2, player.toString());
                    try (ResultSet results = statement.executeQuery()) {
                        selected = results.next() ? Optional.of(readClaim(results)) : Optional.empty();
                    }
                }
                if (selected.isEmpty()) {
                    connection.rollback();
                    return Optional.empty();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE auction_claims SET claimed_at = ?
                        WHERE id = ? AND player_uuid = ? AND claimed_at IS NULL
                        """)) {
                    statement.setLong(1, now);
                    statement.setLong(2, claimId);
                    statement.setString(3, player.toString());
                    if (statement.executeUpdate() != 1) {
                        connection.rollback();
                        return Optional.empty();
                    }
                }
                connection.commit();
                return selected;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static Optional<AuctionListing> find(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LISTING_SELECT + " WHERE listing.id = ?")) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readListing(results)) : Optional.empty();
            }
        }
    }

    private static List<AuctionListing> readListings(PreparedStatement statement) throws SQLException {
        try (ResultSet results = statement.executeQuery()) {
            List<AuctionListing> listings = new ArrayList<>();
            while (results.next()) {
                listings.add(readListing(results));
            }
            return List.copyOf(listings);
        }
    }

    private static AuctionListing readListing(ResultSet results) throws SQLException {
        long buyout = results.getLong("buyout_cents");
        Long nullableBuyout = results.wasNull() ? null : buyout;
        String bidder = results.getString("current_bidder_uuid");
        return new AuctionListing(
                results.getLong("id"), UUID.fromString(results.getString("seller_uuid")),
                results.getString("seller_name"), results.getBytes("item_data"),
                results.getString("item_key"), results.getString("item_name"),
                results.getInt("item_quantity"), results.getLong("starting_bid_cents"),
                nullableBuyout, results.getLong("current_bid_cents"),
                bidder == null ? null : UUID.fromString(bidder), results.getString("bidder_name"),
                AuctionState.valueOf(results.getString("state")),
                Instant.ofEpochMilli(results.getLong("created_at")),
                Instant.ofEpochMilli(results.getLong("ends_at")));
    }

    private static AuctionClaim readClaim(ResultSet results) throws SQLException {
        return new AuctionClaim(
                results.getLong("id"), results.getLong("listing_id"), results.getBytes("item_data"),
                results.getString("item_key"), results.getString("item_name"),
                results.getInt("item_quantity"), Instant.ofEpochMilli(results.getLong("created_at")));
    }

    private static long insertListing(
            Connection connection, UUID seller, byte[] itemData, String itemKey, String itemName,
            int quantity, long startingBid, Long buyout, long now, long endsAt) throws SQLException {
        String sql = """
                INSERT INTO auction_listings(
                    seller_uuid, item_data, item_key, item_name, item_quantity,
                    starting_bid_cents, buyout_cents, created_at, ends_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, seller.toString());
            statement.setBytes(2, itemData);
            statement.setString(3, itemKey);
            statement.setString(4, itemName);
            statement.setInt(5, quantity);
            statement.setLong(6, startingBid);
            if (buyout == null) {
                statement.setNull(7, java.sql.Types.BIGINT);
            } else {
                statement.setLong(7, buyout);
            }
            statement.setLong(8, now);
            statement.setLong(9, endsAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Auction listing insert did not return an id");
                }
                return keys.getLong(1);
            }
        }
    }

    private static void updateBid(Connection connection, long listingId, UUID bidder, long amount)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE auction_listings SET current_bidder_uuid = ?, current_bid_cents = ? WHERE id = ?
                """)) {
            statement.setString(1, bidder.toString());
            statement.setLong(2, amount);
            statement.setLong(3, listingId);
            statement.executeUpdate();
        }
    }

    private static void insertBid(Connection connection, long listingId, UUID bidder, long amount, long now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO auction_bids(listing_id, bidder_uuid, amount_cents, created_at)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setLong(1, listingId);
            statement.setString(2, bidder.toString());
            statement.setLong(3, amount);
            statement.setLong(4, now);
            statement.executeUpdate();
        }
    }

    private static void settleListing(
            Connection connection, long listingId, AuctionState state,
            UUID winner, long finalPrice, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE auction_listings
                SET state = ?, current_bidder_uuid = COALESCE(?, current_bidder_uuid),
                    current_bid_cents = CASE WHEN ? > 0 THEN ? ELSE current_bid_cents END,
                    settled_at = ?
                WHERE id = ? AND state = 'ACTIVE'
                """)) {
            statement.setString(1, state.name());
            if (winner == null) {
                statement.setNull(2, java.sql.Types.VARCHAR);
            } else {
                statement.setString(2, winner.toString());
            }
            statement.setLong(3, finalPrice);
            statement.setLong(4, finalPrice);
            statement.setLong(5, now);
            statement.setLong(6, listingId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Auction listing was settled concurrently");
            }
        }
    }

    private static void insertClaim(Connection connection, AuctionListing listing, UUID player, long now)
            throws SQLException {
        String sql = """
                INSERT INTO auction_claims(
                    listing_id, player_uuid, item_data, item_key, item_name, item_quantity, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, listing.id());
            statement.setString(2, player.toString());
            statement.setBytes(3, listing.itemData());
            statement.setString(4, listing.itemKey());
            statement.setString(5, listing.itemName());
            statement.setInt(6, listing.itemQuantity());
            statement.setLong(7, now);
            statement.executeUpdate();
        }
    }

    private static AuctionResult availability(AuctionListing listing, long now) {
        if (listing.state() != AuctionState.ACTIVE) {
            return AuctionResult.LISTING_INACTIVE;
        }
        return listing.endsAt().toEpochMilli() <= now
                ? AuctionResult.LISTING_EXPIRED : AuctionResult.SUCCESS;
    }

    private static int activeCount(Connection connection, UUID seller) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM auction_listings WHERE seller_uuid = ? AND state = 'ACTIVE'
                """)) {
            statement.setString(1, seller.toString());
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getInt(1);
            }
        }
    }

    private static boolean accountExists(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM accounts WHERE player_uuid = ?")) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static boolean debitPlayer(Connection connection, UUID player, long amount) throws SQLException {
        if (amount == 0) {
            return true;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE accounts SET balance_cents = balance_cents - ?
                WHERE player_uuid = ? AND balance_cents >= ?
                """)) {
            statement.setLong(1, amount);
            statement.setString(2, player.toString());
            statement.setLong(3, amount);
            return statement.executeUpdate() == 1;
        }
    }

    private static void creditPlayer(Connection connection, UUID player, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance_cents = balance_cents + ? WHERE player_uuid = ?")) {
            statement.setLong(1, amount);
            statement.setString(2, player.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Auction account not found");
            }
        }
    }

    private static long playerBalance(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_cents FROM accounts WHERE player_uuid = ?")) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("Auction account not found");
                }
                return results.getLong(1);
            }
        }
    }

    private static void insertLedger(
            Connection connection, UUID player, UUID counterparty,
            long amount, LedgerEntryType type, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger_entries(player_uuid, counterparty_uuid, amount_cents, entry_type, created_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, player.toString());
            if (counterparty == null) {
                statement.setNull(2, java.sql.Types.VARCHAR);
            } else {
                statement.setString(2, counterparty.toString());
            }
            statement.setLong(3, amount);
            statement.setString(4, type.name());
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }
}
