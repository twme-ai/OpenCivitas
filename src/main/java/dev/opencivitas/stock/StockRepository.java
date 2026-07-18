package dev.opencivitas.stock;

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

public final class StockRepository {
    private final Database database;

    public StockRepository(Database database) {
        this.database = database;
    }

    public StockOperation<StockExchange> createExchange(
            UUID actor, String businessSlug, String slug, String displayName,
            int feeBasisPoints, int maximumFeeBasisPoints, long now) throws SQLException {
        if (!validId(slug, 32) || !validName(displayName, 64)
                || feeBasisPoints < 0 || feeBasisPoints > maximumFeeBasisPoints) {
            return StockOperation.result(StockResult.INVALID_VALUE);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                BusinessRow business = business(connection, businessSlug);
                if (business == null) return rollback(connection, StockResult.BUSINESS_NOT_FOUND);
                if (!business.active()) return rollback(connection, StockResult.BUSINESS_INACTIVE);
                if (!canManageBusiness(connection, business.id(), actor)) {
                    return rollback(connection, StockResult.NO_PERMISSION);
                }
                if (exchange(connection, slug) != null) return rollback(connection, StockResult.EXCHANGE_EXISTS);
                long id;
                String sql = """
                        INSERT INTO stock_exchanges(
                            slug, display_name, operator_business_id, fee_basis_points,
                            status, created_by, created_at)
                        VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
                        """;
                try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, slug.toLowerCase(java.util.Locale.ROOT));
                    statement.setString(2, displayName.trim());
                    statement.setLong(3, business.id());
                    statement.setInt(4, feeBasisPoints);
                    statement.setString(5, actor.toString());
                    statement.setLong(6, now);
                    statement.executeUpdate();
                    id = generatedId(statement);
                }
                connection.commit();
                return StockOperation.success(new StockExchange(id, slug.toLowerCase(java.util.Locale.ROOT),
                        displayName.trim(), business.id(), business.displayName(), feeBasisPoints,
                        StockExchangeStatus.ACTIVE, Instant.ofEpochMilli(now)));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public StockOperation<StockListing> applyListing(
            UUID actor, String exchangeSlug, String businessSlug, String symbol,
            long shares, long initialPriceCents, long maximumShares, long now) throws SQLException {
        String normalized = symbol == null ? "" : symbol.toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9.-]{0,9}")
                || shares < 1 || shares > maximumShares || initialPriceCents < 1) {
            return StockOperation.result(StockResult.INVALID_VALUE);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                ExchangeRow exchange = exchange(connection, exchangeSlug);
                if (exchange == null) return rollback(connection, StockResult.EXCHANGE_NOT_FOUND);
                if (exchange.status() != StockExchangeStatus.ACTIVE) {
                    return rollback(connection, StockResult.EXCHANGE_INACTIVE);
                }
                BusinessRow issuer = business(connection, businessSlug);
                if (issuer == null) return rollback(connection, StockResult.BUSINESS_NOT_FOUND);
                if (!issuer.active()) return rollback(connection, StockResult.BUSINESS_INACTIVE);
                if (!canManageBusiness(connection, issuer.id(), actor)) {
                    return rollback(connection, StockResult.NO_PERMISSION);
                }
                if (listing(connection, normalized) != null) {
                    return rollback(connection, StockResult.LISTING_EXISTS);
                }
                long id;
                String sql = """
                        INSERT INTO stock_listings(
                            exchange_id, issuer_business_id, symbol, authorized_shares,
                            treasury_shares, status, initial_price_cents, last_price_cents,
                            applied_by, applied_at)
                        VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
                        """;
                try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, exchange.id());
                    statement.setLong(2, issuer.id());
                    statement.setString(3, normalized);
                    statement.setLong(4, shares);
                    statement.setLong(5, shares);
                    statement.setLong(6, initialPriceCents);
                    statement.setLong(7, initialPriceCents);
                    statement.setString(8, actor.toString());
                    statement.setLong(9, now);
                    statement.executeUpdate();
                    id = generatedId(statement);
                }
                connection.commit();
                return StockOperation.success(new StockListing(id, normalized, exchange.id(),
                        exchange.displayName(), exchange.feeBasisPoints(), issuer.id(), issuer.displayName(),
                        shares, shares, StockListingStatus.PENDING, initialPriceCents, initialPriceCents,
                        null, Instant.ofEpochMilli(now), null));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public StockOperation<StockListing> reviewListing(
            UUID actor, String symbol, boolean approve, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                ListingRow listing = listing(connection, symbol);
                if (listing == null) return rollback(connection, StockResult.LISTING_NOT_FOUND);
                if (listing.status() != StockListingStatus.PENDING) {
                    return rollback(connection, StockResult.LISTING_INACTIVE);
                }
                ExchangeRow exchange = exchange(connection, listing.exchangeId());
                if (!canManageBusiness(connection, exchange.operatorBusinessId(), actor)) {
                    return rollback(connection, StockResult.NO_PERMISSION);
                }
                StockListingStatus status = approve ? StockListingStatus.ACTIVE : StockListingStatus.REJECTED;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE stock_listings SET status = ?, approved_by = ?, approved_at = ? WHERE id = ?
                        """)) {
                    statement.setString(1, status.name());
                    statement.setString(2, actor.toString());
                    statement.setLong(3, now);
                    statement.setLong(4, listing.id());
                    statement.executeUpdate();
                }
                if (approve) {
                    insertOrder(connection, listing.id(), null, true, StockOrderSide.SELL,
                            listing.initialPriceCents(), listing.treasuryShares(), 0, now);
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE stock_listings SET treasury_shares = 0 WHERE id = ?")) {
                        statement.setLong(1, listing.id());
                        statement.executeUpdate();
                    }
                }
                connection.commit();
                return StockOperation.success(findListing(symbol).orElseThrow());
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public StockOperation<StockOrderPlacement> placeOrder(
            UUID player, String symbol, StockOrderSide side, long quantity, long limitPriceCents,
            int maximumOpenOrders, long maximumQuantity, long now) throws SQLException {
        if (side == null || quantity < 1 || quantity > maximumQuantity || limitPriceCents < 1) {
            return StockOperation.result(StockResult.INVALID_VALUE);
        }
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!citizenExists(connection, player)) return rollback(connection, StockResult.CITIZEN_NOT_FOUND);
                ListingRow listing = listing(connection, symbol);
                if (listing == null) return rollback(connection, StockResult.LISTING_NOT_FOUND);
                ExchangeRow exchange = exchange(connection, listing.exchangeId());
                if (exchange.status() != StockExchangeStatus.ACTIVE) {
                    return rollback(connection, StockResult.EXCHANGE_INACTIVE);
                }
                if (listing.status() != StockListingStatus.ACTIVE) {
                    return rollback(connection, listing.status() == StockListingStatus.PENDING
                            ? StockResult.LISTING_PENDING : StockResult.LISTING_INACTIVE);
                }
                if (openOrderCount(connection, player) >= maximumOpenOrders) {
                    return rollback(connection, StockResult.OPEN_ORDER_LIMIT);
                }
                long escrow = 0;
                try {
                    if (side == StockOrderSide.BUY) {
                        long unitFee = fee(limitPriceCents, exchange.feeBasisPoints());
                        escrow = Math.multiplyExact(quantity, Math.addExact(limitPriceCents, unitFee));
                        if (withdrawPlayer(connection, player, escrow) == 0) {
                            return rollback(connection, StockResult.INSUFFICIENT_FUNDS);
                        }
                        insertPlayerLedger(connection, player, -escrow, LedgerEntryType.STOCK_ESCROW, now);
                    } else if (takeHolding(connection, listing.id(), player, quantity, now) == 0) {
                        return rollback(connection, StockResult.INSUFFICIENT_SHARES);
                    }
                } catch (ArithmeticException exception) {
                    return rollback(connection, StockResult.INVALID_VALUE);
                }
                long orderId = insertOrder(connection, listing.id(), player, false, side,
                        limitPriceCents, quantity, escrow, now);
                List<StockTrade> trades = match(connection, orderId, listing, exchange, now);
                StockOrder order = order(connection, orderId);
                connection.commit();
                return StockOperation.success(new StockOrderPlacement(order, trades));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public StockOperation<StockOrder> cancelOrder(UUID player, long orderId, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                OrderRow order = orderRow(connection, orderId);
                if (order == null) return rollback(connection, StockResult.ORDER_NOT_FOUND);
                if (order.ownerId() == null || !order.ownerId().equals(player)) {
                    return rollback(connection, StockResult.NO_PERMISSION);
                }
                if (!open(order.status())) return rollback(connection, StockResult.ORDER_NOT_OPEN);
                if (order.side() == StockOrderSide.BUY && order.escrowCents() > 0) {
                    depositPlayer(connection, player, order.escrowCents());
                    insertPlayerLedger(connection, player, order.escrowCents(), LedgerEntryType.STOCK_REFUND, now);
                } else if (order.side() == StockOrderSide.SELL && order.remainingQuantity() > 0) {
                    addHolding(connection, order.listingId(), player, order.remainingQuantity(), now);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE stock_orders SET remaining_quantity = 0, escrow_cents = 0,
                            status = 'CANCELLED', closed_at = ? WHERE id = ?
                        """)) {
                    statement.setLong(1, now);
                    statement.setLong(2, orderId);
                    statement.executeUpdate();
                }
                StockOrder cancelled = order(connection, orderId);
                connection.commit();
                return StockOperation.success(cancelled);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public StockOperation<StockDividend> payDividend(
            UUID actor, String symbol, long perShareCents, long now) throws SQLException {
        if (perShareCents < 1) return StockOperation.result(StockResult.INVALID_VALUE);
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                ListingRow listing = listing(connection, symbol);
                if (listing == null) return rollback(connection, StockResult.LISTING_NOT_FOUND);
                if (!canManageBusiness(connection, listing.issuerBusinessId(), actor)) {
                    return rollback(connection, StockResult.NO_PERMISSION);
                }
                List<HolderRow> holders = holderRows(connection, listing.id());
                if (holders.isEmpty()) return rollback(connection, StockResult.NO_SHAREHOLDERS);
                long paidShares = 0;
                long total = 0;
                try {
                    for (HolderRow holder : holders) {
                        paidShares = Math.addExact(paidShares, holder.quantity());
                        total = Math.addExact(total, Math.multiplyExact(holder.quantity(), perShareCents));
                    }
                } catch (ArithmeticException exception) {
                    return rollback(connection, StockResult.INVALID_VALUE);
                }
                if (updateBusinessBalance(connection, listing.issuerBusinessId(), -total, true) == 0) {
                    return rollback(connection, StockResult.INSUFFICIENT_BUSINESS_FUNDS);
                }
                long dividendId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO stock_dividends(
                            listing_id, issuer_business_id, declared_by, per_share_cents,
                            paid_shares, total_cents, recipient_count, paid_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, listing.id());
                    statement.setLong(2, listing.issuerBusinessId());
                    statement.setString(3, actor.toString());
                    statement.setLong(4, perShareCents);
                    statement.setLong(5, paidShares);
                    statement.setLong(6, total);
                    statement.setInt(7, holders.size());
                    statement.setLong(8, now);
                    statement.executeUpdate();
                    dividendId = generatedId(statement);
                }
                for (HolderRow holder : holders) {
                    long amount = Math.multiplyExact(holder.quantity(), perShareCents);
                    depositPlayer(connection, holder.playerId(), amount);
                    insertPlayerLedger(connection, holder.playerId(), amount, LedgerEntryType.STOCK_DIVIDEND, now);
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO stock_dividend_payments(
                                dividend_id, holder_uuid, shares, amount_cents) VALUES (?, ?, ?, ?)
                            """)) {
                        statement.setLong(1, dividendId);
                        statement.setString(2, holder.playerId().toString());
                        statement.setLong(3, holder.quantity());
                        statement.setLong(4, amount);
                        statement.executeUpdate();
                    }
                }
                insertBusinessLedger(connection, listing.issuerBusinessId(), actor, null,
                        -total, "STOCK_DIVIDEND", now);
                connection.commit();
                return StockOperation.success(new StockDividend(dividendId, listing.symbol(), perShareCents,
                        total, holders.size(), paidShares, Instant.ofEpochMilli(now)));
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public StockOperation<StockListing> setListingHalted(
            UUID actor, String symbol, boolean halted, boolean administrator, long now) throws SQLException {
        try (Connection connection = database.openConnection()) {
            ListingRow listing = listing(connection, symbol);
            if (listing == null) return StockOperation.result(StockResult.LISTING_NOT_FOUND);
            ExchangeRow exchange = exchange(connection, listing.exchangeId());
            if (!administrator && !canManageBusiness(connection, exchange.operatorBusinessId(), actor)) {
                return StockOperation.result(StockResult.NO_PERMISSION);
            }
            StockListingStatus required = halted ? StockListingStatus.ACTIVE : StockListingStatus.HALTED;
            if (listing.status() != required) return StockOperation.result(StockResult.LISTING_INACTIVE);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE stock_listings SET status = ? WHERE id = ?")) {
                statement.setString(1, halted ? StockListingStatus.HALTED.name() : StockListingStatus.ACTIVE.name());
                statement.setLong(2, listing.id());
                statement.executeUpdate();
            }
            return StockOperation.success(findListing(symbol).orElseThrow());
        }
    }

    public List<StockExchange> exchanges() throws SQLException {
        String sql = """
                SELECT exchange.id, exchange.slug, exchange.display_name, exchange.operator_business_id,
                    business.display_name AS operator_name, exchange.fee_basis_points,
                    exchange.status, exchange.created_at
                FROM stock_exchanges exchange
                JOIN businesses business ON business.id = exchange.operator_business_id
                ORDER BY exchange.display_name COLLATE NOCASE
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            List<StockExchange> exchanges = new ArrayList<>();
            while (results.next()) exchanges.add(readExchange(results));
            return List.copyOf(exchanges);
        }
    }

    public List<StockListing> listings() throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(listingSelect()
                     + " ORDER BY listing.symbol COLLATE NOCASE");
             ResultSet results = statement.executeQuery()) {
            List<StockListing> listings = new ArrayList<>();
            while (results.next()) listings.add(readListing(results));
            return List.copyOf(listings);
        }
    }

    public Optional<StockListing> findListing(String symbol) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(listingSelect()
                     + " WHERE listing.symbol = ? COLLATE NOCASE")) {
            statement.setString(1, symbol);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readListing(results)) : Optional.empty();
            }
        }
    }

    public Optional<StockQuote> quote(String symbol) throws SQLException {
        try (Connection connection = database.openConnection()) {
            StockListing listing = findListing(connection, symbol);
            if (listing == null) return Optional.empty();
            Long bid = bookPrice(connection, listing.id(), StockOrderSide.BUY, true);
            Long ask = bookPrice(connection, listing.id(), StockOrderSide.SELL, false);
            List<HolderRow> holders = holderRows(connection, listing.id());
            long shares = holders.stream().mapToLong(HolderRow::quantity).sum();
            return Optional.of(new StockQuote(listing, bid, ask, shares, holders.size()));
        }
    }

    public List<StockPosition> portfolio(UUID player) throws SQLException {
        String sql = """
                SELECT listing.symbol, business.display_name AS issuer_name, listing.last_price_cents,
                    COALESCE(holding.quantity, 0) AS available_quantity,
                    COALESCE(SUM(CASE WHEN orders.side = 'SELL' AND orders.status IN ('OPEN', 'PARTIAL')
                        THEN orders.remaining_quantity ELSE 0 END), 0) AS escrow_quantity
                FROM stock_listings listing
                JOIN businesses business ON business.id = listing.issuer_business_id
                LEFT JOIN stock_holdings holding
                    ON holding.listing_id = listing.id AND holding.holder_uuid = ?
                LEFT JOIN stock_orders orders
                    ON orders.listing_id = listing.id AND orders.owner_uuid = ?
                GROUP BY listing.id, listing.symbol, business.display_name,
                    listing.last_price_cents, holding.quantity
                HAVING available_quantity > 0 OR escrow_quantity > 0
                ORDER BY listing.symbol
                """;
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            statement.setString(2, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<StockPosition> positions = new ArrayList<>();
                while (results.next()) positions.add(new StockPosition(
                        results.getString("symbol"), results.getString("issuer_name"),
                        results.getLong("available_quantity"), results.getLong("escrow_quantity"),
                        results.getLong("last_price_cents")));
                return List.copyOf(positions);
            }
        }
    }

    public List<StockOrder> orders(UUID player, boolean openOnly) throws SQLException {
        String sql = orderSelect() + " WHERE orders.owner_uuid = ? "
                + (openOnly ? "AND orders.status IN ('OPEN', 'PARTIAL') " : "")
                + "ORDER BY orders.created_at DESC, orders.id DESC";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                List<StockOrder> orders = new ArrayList<>();
                while (results.next()) orders.add(readOrder(results));
                return List.copyOf(orders);
            }
        }
    }

    public List<StockTrade> trades(String symbol, int limit) throws SQLException {
        String sql = tradeSelect() + " WHERE listing.symbol = ? COLLATE NOCASE "
                + "ORDER BY trade.executed_at DESC, trade.id DESC LIMIT ?";
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, symbol);
            statement.setInt(2, limit);
            try (ResultSet results = statement.executeQuery()) {
                List<StockTrade> trades = new ArrayList<>();
                while (results.next()) trades.add(readTrade(results));
                return List.copyOf(trades);
            }
        }
    }

    public List<StockShareholder> shareholders(String symbol) throws SQLException {
        try (Connection connection = database.openConnection()) {
            ListingRow listing = listing(connection, symbol);
            if (listing == null) return List.of();
            List<StockShareholder> holders = new ArrayList<>();
            for (HolderRow row : holderRows(connection, listing.id())) {
                holders.add(new StockShareholder(row.playerId(), row.playerName(), row.quantity()));
            }
            return List.copyOf(holders);
        }
    }

    private static List<StockTrade> match(
            Connection connection, long newOrderId, ListingRow listing, ExchangeRow exchange, long now)
            throws SQLException {
        List<StockTrade> trades = new ArrayList<>();
        while (true) {
            OrderRow current = orderRow(connection, newOrderId);
            if (current == null || current.remainingQuantity() == 0 || !open(current.status())) break;
            OrderRow counter = counterOrder(connection, current);
            if (counter == null) break;
            OrderRow buyer = current.side() == StockOrderSide.BUY ? current : counter;
            OrderRow seller = current.side() == StockOrderSide.SELL ? current : counter;
            long quantity = Math.min(buyer.remainingQuantity(), seller.remainingQuantity());
            long price = counter.limitPriceCents();
            long notional;
            long buyerFee;
            long sellerFee;
            try {
                notional = Math.multiplyExact(quantity, price);
                buyerFee = fee(notional, exchange.feeBasisPoints());
                sellerFee = fee(notional, exchange.feeBasisPoints());
            } catch (ArithmeticException exception) {
                throw new SQLException("Stock fill value is out of range", exception);
            }
            long buyerDebit = Math.addExact(notional, buyerFee);
            if (debitBuyEscrow(connection, buyer.id(), buyerDebit) == 0) {
                throw new SQLException("Buy order escrow is insufficient");
            }
            long sellerProceeds = notional - sellerFee;
            if (seller.issuerSale()) {
                if (sellerProceeds > 0) updateBusinessBalance(
                        connection, listing.issuerBusinessId(), sellerProceeds, false);
                if (sellerProceeds > 0) insertBusinessLedger(connection, listing.issuerBusinessId(),
                        null, buyer.ownerId(), sellerProceeds, "STOCK_ISSUE", now);
            } else {
                if (sellerProceeds > 0) depositPlayer(connection, seller.ownerId(), sellerProceeds);
                if (sellerProceeds > 0) insertPlayerLedger(connection, seller.ownerId(),
                        sellerProceeds, LedgerEntryType.STOCK_SALE, now);
            }
            long exchangeFees = Math.addExact(buyerFee, sellerFee);
            if (exchangeFees > 0) {
                updateBusinessBalance(connection, exchange.operatorBusinessId(), exchangeFees, false);
                insertBusinessLedger(connection, exchange.operatorBusinessId(), null,
                        buyer.ownerId(), exchangeFees, "STOCK_FEE", now);
            }
            addHolding(connection, listing.id(), buyer.ownerId(), quantity, now);
            updateOrderFill(connection, buyer, quantity, now);
            updateOrderFill(connection, seller, quantity, now);
            long tradeId = insertTrade(connection, listing.id(), buyer, seller, quantity,
                    price, buyerFee, sellerFee, now);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE stock_listings SET last_price_cents = ?, last_trade_at = ? WHERE id = ?
                    """)) {
                statement.setLong(1, price);
                statement.setLong(2, now);
                statement.setLong(3, listing.id());
                statement.executeUpdate();
            }
            rebalanceBuyEscrow(connection, buyer.id(), exchange.feeBasisPoints(), now);
            trades.add(trade(connection, tradeId));
        }
        return List.copyOf(trades);
    }

    private static OrderRow counterOrder(Connection connection, OrderRow current) throws SQLException {
        String comparison = current.side() == StockOrderSide.BUY
                ? "side = 'SELL' AND limit_price_cents <= ? ORDER BY limit_price_cents ASC"
                : "side = 'BUY' AND limit_price_cents >= ? ORDER BY limit_price_cents DESC";
        String sql = "SELECT id, listing_id, owner_uuid, issuer_sale, side, limit_price_cents, "
                + "original_quantity, remaining_quantity, escrow_cents, status, created_at, closed_at "
                + "FROM stock_orders WHERE listing_id = ? AND status IN ('OPEN', 'PARTIAL') AND "
                + comparison + ", created_at ASC, id ASC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, current.listingId());
            statement.setLong(2, current.limitPriceCents());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    OrderRow candidate = readOrderRow(results);
                    if (current.ownerId() == null || candidate.ownerId() == null
                            || !current.ownerId().equals(candidate.ownerId())) return candidate;
                }
                return null;
            }
        }
    }

    private static void rebalanceBuyEscrow(
            Connection connection, long orderId, int feeBasisPoints, long now) throws SQLException {
        OrderRow buyer = orderRow(connection, orderId);
        long required;
        try {
            long unitFee = fee(buyer.limitPriceCents(), feeBasisPoints);
            required = Math.multiplyExact(buyer.remainingQuantity(),
                    Math.addExact(buyer.limitPriceCents(), unitFee));
        } catch (ArithmeticException exception) {
            throw new SQLException("Buy escrow requirement is out of range", exception);
        }
        long refund = buyer.escrowCents() - required;
        if (refund < 0) throw new SQLException("Buy order escrow fell below its required reserve");
        if (refund > 0) {
            depositPlayer(connection, buyer.ownerId(), refund);
            insertPlayerLedger(connection, buyer.ownerId(), refund, LedgerEntryType.STOCK_REFUND, now);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE stock_orders SET escrow_cents = escrow_cents - ? WHERE id = ?")) {
                statement.setLong(1, refund);
                statement.setLong(2, orderId);
                statement.executeUpdate();
            }
        }
    }

    private static void updateOrderFill(
            Connection connection, OrderRow order, long quantity, long now) throws SQLException {
        long remaining = order.remainingQuantity() - quantity;
        StockOrderStatus status = remaining == 0 ? StockOrderStatus.FILLED : StockOrderStatus.PARTIAL;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE stock_orders SET remaining_quantity = ?, status = ?, closed_at = ? WHERE id = ?
                """)) {
            statement.setLong(1, remaining);
            statement.setString(2, status.name());
            if (remaining == 0) statement.setLong(3, now);
            else statement.setNull(3, java.sql.Types.BIGINT);
            statement.setLong(4, order.id());
            statement.executeUpdate();
        }
    }

    private static long insertTrade(
            Connection connection, long listingId, OrderRow buyer, OrderRow seller,
            long quantity, long price, long buyerFee, long sellerFee, long now) throws SQLException {
        String sql = """
                INSERT INTO stock_trades(
                    listing_id, buy_order_id, sell_order_id, buyer_uuid, seller_uuid,
                    seller_business_id, quantity, price_cents, buyer_fee_cents, seller_fee_cents, executed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, listingId);
            statement.setLong(2, buyer.id());
            statement.setLong(3, seller.id());
            statement.setString(4, buyer.ownerId().toString());
            if (seller.issuerSale()) statement.setNull(5, java.sql.Types.VARCHAR);
            else statement.setString(5, seller.ownerId().toString());
            if (seller.issuerSale()) statement.setLong(6, listing(connection, listingId).issuerBusinessId());
            else statement.setNull(6, java.sql.Types.BIGINT);
            statement.setLong(7, quantity);
            statement.setLong(8, price);
            statement.setLong(9, buyerFee);
            statement.setLong(10, sellerFee);
            statement.setLong(11, now);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private static long insertOrder(
            Connection connection, long listingId, UUID owner, boolean issuerSale,
            StockOrderSide side, long price, long quantity, long escrow, long now) throws SQLException {
        String sql = """
                INSERT INTO stock_orders(
                    listing_id, owner_uuid, issuer_sale, side, limit_price_cents,
                    original_quantity, remaining_quantity, escrow_cents, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, listingId);
            if (owner == null) statement.setNull(2, java.sql.Types.VARCHAR);
            else statement.setString(2, owner.toString());
            statement.setInt(3, issuerSale ? 1 : 0);
            statement.setString(4, side.name());
            statement.setLong(5, price);
            statement.setLong(6, quantity);
            statement.setLong(7, quantity);
            statement.setLong(8, escrow);
            statement.setLong(9, now);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private static StockOrder order(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(orderSelect() + " WHERE orders.id = ?")) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) throw new SQLException("Stock order not found after update");
                return readOrder(results);
            }
        }
    }

    private static StockTrade trade(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(tradeSelect() + " WHERE trade.id = ?")) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) throw new SQLException("Stock trade not found after insert");
                return readTrade(results);
            }
        }
    }

    private static StockListing findListing(Connection connection, String symbol) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(listingSelect()
                + " WHERE listing.symbol = ? COLLATE NOCASE")) {
            statement.setString(1, symbol);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? readListing(results) : null;
            }
        }
    }

    private static String listingSelect() {
        return """
                SELECT listing.id, listing.symbol, listing.exchange_id,
                    exchange.display_name AS exchange_name, exchange.fee_basis_points,
                    listing.issuer_business_id, issuer.display_name AS issuer_name,
                    listing.authorized_shares, listing.treasury_shares, listing.status,
                    listing.initial_price_cents, listing.last_price_cents, listing.last_trade_at,
                    listing.applied_at, listing.approved_at
                FROM stock_listings listing
                JOIN stock_exchanges exchange ON exchange.id = listing.exchange_id
                JOIN businesses issuer ON issuer.id = listing.issuer_business_id
                """;
    }

    private static String orderSelect() {
        return """
                SELECT orders.id, listing.symbol, orders.owner_uuid, owner.last_name AS owner_name,
                    orders.issuer_sale, orders.side, orders.limit_price_cents,
                    orders.original_quantity, orders.remaining_quantity, orders.escrow_cents,
                    orders.status, orders.created_at, orders.closed_at
                FROM stock_orders orders
                JOIN stock_listings listing ON listing.id = orders.listing_id
                LEFT JOIN players owner ON owner.uuid = orders.owner_uuid
                """;
    }

    private static String tradeSelect() {
        return """
                SELECT trade.id, listing.symbol, trade.buy_order_id, trade.sell_order_id,
                    trade.buyer_uuid, buyer.last_name AS buyer_name,
                    trade.seller_uuid, seller.last_name AS seller_name,
                    seller_business.display_name AS seller_business_name,
                    trade.quantity, trade.price_cents, trade.buyer_fee_cents,
                    trade.seller_fee_cents, trade.executed_at
                FROM stock_trades trade
                JOIN stock_listings listing ON listing.id = trade.listing_id
                JOIN players buyer ON buyer.uuid = trade.buyer_uuid
                LEFT JOIN players seller ON seller.uuid = trade.seller_uuid
                LEFT JOIN businesses seller_business ON seller_business.id = trade.seller_business_id
                """;
    }

    private static StockExchange readExchange(ResultSet results) throws SQLException {
        return new StockExchange(results.getLong("id"), results.getString("slug"),
                results.getString("display_name"), results.getLong("operator_business_id"),
                results.getString("operator_name"), results.getInt("fee_basis_points"),
                StockExchangeStatus.valueOf(results.getString("status")),
                Instant.ofEpochMilli(results.getLong("created_at")));
    }

    private static StockListing readListing(ResultSet results) throws SQLException {
        return new StockListing(results.getLong("id"), results.getString("symbol"),
                results.getLong("exchange_id"), results.getString("exchange_name"),
                results.getInt("fee_basis_points"), results.getLong("issuer_business_id"),
                results.getString("issuer_name"), results.getLong("authorized_shares"),
                results.getLong("treasury_shares"), StockListingStatus.valueOf(results.getString("status")),
                results.getLong("initial_price_cents"), results.getLong("last_price_cents"),
                instant(results, "last_trade_at"), Instant.ofEpochMilli(results.getLong("applied_at")),
                instant(results, "approved_at"));
    }

    private static StockOrder readOrder(ResultSet results) throws SQLException {
        String owner = results.getString("owner_uuid");
        return new StockOrder(results.getLong("id"), results.getString("symbol"),
                owner == null ? null : UUID.fromString(owner), results.getString("owner_name"),
                results.getInt("issuer_sale") != 0, StockOrderSide.valueOf(results.getString("side")),
                results.getLong("limit_price_cents"), results.getLong("original_quantity"),
                results.getLong("remaining_quantity"), results.getLong("escrow_cents"),
                StockOrderStatus.valueOf(results.getString("status")),
                Instant.ofEpochMilli(results.getLong("created_at")), instant(results, "closed_at"));
    }

    private static StockTrade readTrade(ResultSet results) throws SQLException {
        String seller = results.getString("seller_uuid");
        return new StockTrade(results.getLong("id"), results.getString("symbol"),
                results.getLong("buy_order_id"), results.getLong("sell_order_id"),
                UUID.fromString(results.getString("buyer_uuid")), results.getString("buyer_name"),
                seller == null ? null : UUID.fromString(seller), results.getString("seller_name"),
                results.getString("seller_business_name"), results.getLong("quantity"),
                results.getLong("price_cents"), results.getLong("buyer_fee_cents"),
                results.getLong("seller_fee_cents"), Instant.ofEpochMilli(results.getLong("executed_at")));
    }

    private static List<HolderRow> holderRows(Connection connection, long listingId) throws SQLException {
        String sql = """
                SELECT shares.holder_uuid, player.last_name, SUM(shares.quantity) AS quantity
                FROM (
                    SELECT holder_uuid, quantity FROM stock_holdings WHERE listing_id = ?
                    UNION ALL
                    SELECT owner_uuid, remaining_quantity FROM stock_orders
                    WHERE listing_id = ? AND side = 'SELL' AND issuer_sale = 0
                        AND status IN ('OPEN', 'PARTIAL')
                ) shares
                JOIN players player ON player.uuid = shares.holder_uuid
                GROUP BY shares.holder_uuid, player.last_name
                HAVING quantity > 0
                ORDER BY quantity DESC, player.last_name COLLATE NOCASE
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, listingId);
            statement.setLong(2, listingId);
            try (ResultSet results = statement.executeQuery()) {
                List<HolderRow> holders = new ArrayList<>();
                while (results.next()) holders.add(new HolderRow(UUID.fromString(
                        results.getString("holder_uuid")), results.getString("last_name"),
                        results.getLong("quantity")));
                return List.copyOf(holders);
            }
        }
    }

    private static Long bookPrice(
            Connection connection, long listingId, StockOrderSide side, boolean descending) throws SQLException {
        String sql = "SELECT limit_price_cents FROM stock_orders WHERE listing_id = ? AND side = ? "
                + "AND status IN ('OPEN', 'PARTIAL') ORDER BY limit_price_cents "
                + (descending ? "DESC" : "ASC") + ", created_at, id LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, listingId);
            statement.setString(2, side.name());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getLong(1) : null;
            }
        }
    }

    private static BusinessRow business(Connection connection, String slug) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, display_name, balance_cents, status FROM businesses WHERE slug = ? COLLATE NOCASE")) {
            statement.setString(1, slug);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? new BusinessRow(results.getLong("id"), results.getString("display_name"),
                        results.getLong("balance_cents"), results.getString("status").equals("ACTIVE")) : null;
            }
        }
    }

    private static ExchangeRow exchange(Connection connection, String slug) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, display_name, operator_business_id, fee_basis_points, status
                FROM stock_exchanges WHERE slug = ? COLLATE NOCASE
                """)) {
            statement.setString(1, slug);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? readExchangeRow(results) : null;
            }
        }
    }

    private static ExchangeRow exchange(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, display_name, operator_business_id, fee_basis_points, status
                FROM stock_exchanges WHERE id = ?
                """)) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) throw new SQLException("Stock exchange not found");
                return readExchangeRow(results);
            }
        }
    }

    private static ExchangeRow readExchangeRow(ResultSet results) throws SQLException {
        return new ExchangeRow(results.getLong("id"), results.getString("display_name"),
                results.getLong("operator_business_id"), results.getInt("fee_basis_points"),
                StockExchangeStatus.valueOf(results.getString("status")));
    }

    private static ListingRow listing(Connection connection, String symbol) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, exchange_id, issuer_business_id, symbol, authorized_shares,
                    treasury_shares, status, initial_price_cents
                FROM stock_listings WHERE symbol = ? COLLATE NOCASE
                """)) {
            statement.setString(1, symbol);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? readListingRow(results) : null;
            }
        }
    }

    private static ListingRow listing(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, exchange_id, issuer_business_id, symbol, authorized_shares,
                    treasury_shares, status, initial_price_cents FROM stock_listings WHERE id = ?
                """)) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) throw new SQLException("Stock listing not found");
                return readListingRow(results);
            }
        }
    }

    private static ListingRow readListingRow(ResultSet results) throws SQLException {
        return new ListingRow(results.getLong("id"), results.getLong("exchange_id"),
                results.getLong("issuer_business_id"), results.getString("symbol"),
                results.getLong("authorized_shares"), results.getLong("treasury_shares"),
                StockListingStatus.valueOf(results.getString("status")),
                results.getLong("initial_price_cents"));
    }

    private static OrderRow orderRow(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, listing_id, owner_uuid, issuer_sale, side, limit_price_cents,
                    original_quantity, remaining_quantity, escrow_cents, status, created_at, closed_at
                FROM stock_orders WHERE id = ?
                """)) {
            statement.setLong(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? readOrderRow(results) : null;
            }
        }
    }

    private static OrderRow readOrderRow(ResultSet results) throws SQLException {
        String owner = results.getString("owner_uuid");
        return new OrderRow(results.getLong("id"), results.getLong("listing_id"),
                owner == null ? null : UUID.fromString(owner), results.getInt("issuer_sale") != 0,
                StockOrderSide.valueOf(results.getString("side")), results.getLong("limit_price_cents"),
                results.getLong("original_quantity"), results.getLong("remaining_quantity"),
                results.getLong("escrow_cents"), StockOrderStatus.valueOf(results.getString("status")),
                results.getLong("created_at"));
    }

    private static boolean canManageBusiness(Connection connection, long businessId, UUID player)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT role FROM business_members WHERE business_id = ? AND player_uuid = ?
                """)) {
            statement.setLong(1, businessId);
            statement.setString(2, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) return false;
                return switch (results.getString(1)) {
                    case "PROPRIETOR", "CO_PROPRIETOR", "MANAGER" -> true;
                    default -> false;
                };
            }
        }
    }

    private static boolean citizenExists(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM players WHERE uuid = ?")) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static int openOrderCount(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM stock_orders
                WHERE owner_uuid = ? AND status IN ('OPEN', 'PARTIAL')
                """)) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getInt(1) : 0;
            }
        }
    }

    private static int takeHolding(
            Connection connection, long listingId, UUID player, long quantity, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE stock_holdings SET quantity = quantity - ?, updated_at = ?
                WHERE listing_id = ? AND holder_uuid = ? AND quantity >= ?
                """)) {
            statement.setLong(1, quantity);
            statement.setLong(2, now);
            statement.setLong(3, listingId);
            statement.setString(4, player.toString());
            statement.setLong(5, quantity);
            int changed = statement.executeUpdate();
            if (changed == 1) {
                try (PreparedStatement cleanup = connection.prepareStatement(
                        "DELETE FROM stock_holdings WHERE listing_id = ? AND holder_uuid = ? AND quantity = 0")) {
                    cleanup.setLong(1, listingId);
                    cleanup.setString(2, player.toString());
                    cleanup.executeUpdate();
                }
            }
            return changed;
        }
    }

    private static void addHolding(
            Connection connection, long listingId, UUID player, long quantity, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO stock_holdings(listing_id, holder_uuid, quantity, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(listing_id, holder_uuid) DO UPDATE SET
                    quantity = stock_holdings.quantity + excluded.quantity,
                    updated_at = excluded.updated_at
                """)) {
            statement.setLong(1, listingId);
            statement.setString(2, player.toString());
            statement.setLong(3, quantity);
            statement.setLong(4, now);
            statement.executeUpdate();
        }
    }

    private static int withdrawPlayer(Connection connection, UUID player, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE accounts SET balance_cents = balance_cents - ?
                WHERE player_uuid = ? AND balance_cents >= ?
                """)) {
            statement.setLong(1, amount);
            statement.setString(2, player.toString());
            statement.setLong(3, amount);
            return statement.executeUpdate();
        }
    }

    private static void depositPlayer(Connection connection, UUID player, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE accounts SET balance_cents = balance_cents + ? WHERE player_uuid = ?")) {
            statement.setLong(1, amount);
            statement.setString(2, player.toString());
            if (statement.executeUpdate() != 1) throw new SQLException("Player account not found");
        }
    }

    private static int debitBuyEscrow(Connection connection, long orderId, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE stock_orders SET escrow_cents = escrow_cents - ?
                WHERE id = ? AND side = 'BUY' AND escrow_cents >= ?
                """)) {
            statement.setLong(1, amount);
            statement.setLong(2, orderId);
            statement.setLong(3, amount);
            return statement.executeUpdate();
        }
    }

    private static int updateBusinessBalance(
            Connection connection, long businessId, long delta, boolean checkFunds) throws SQLException {
        String sql = checkFunds
                ? "UPDATE businesses SET balance_cents = balance_cents + ? WHERE id = ? AND balance_cents >= ?"
                : "UPDATE businesses SET balance_cents = balance_cents + ? WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, delta);
            statement.setLong(2, businessId);
            if (checkFunds) statement.setLong(3, -delta);
            return statement.executeUpdate();
        }
    }

    private static void insertPlayerLedger(
            Connection connection, UUID player, long amount, LedgerEntryType type, long now) throws SQLException {
        if (amount == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger_entries(
                    player_uuid, counterparty_uuid, amount_cents, entry_type, created_at)
                VALUES (?, NULL, ?, ?, ?)
                """)) {
            statement.setString(1, player.toString());
            statement.setLong(2, amount);
            statement.setString(3, type.name());
            statement.setLong(4, now);
            statement.executeUpdate();
        }
    }

    private static void insertBusinessLedger(
            Connection connection, long businessId, UUID actor, UUID counterparty,
            long amount, String type, long now) throws SQLException {
        if (amount == 0) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO business_ledger_entries(
                    business_id, actor_uuid, counterparty_uuid, amount_cents, entry_type, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, businessId);
            if (actor == null) statement.setNull(2, java.sql.Types.VARCHAR);
            else statement.setString(2, actor.toString());
            if (counterparty == null) statement.setNull(3, java.sql.Types.VARCHAR);
            else statement.setString(3, counterparty.toString());
            statement.setLong(4, amount);
            statement.setString(5, type);
            statement.setLong(6, now);
            statement.executeUpdate();
        }
    }

    private static long fee(long value, int basisPoints) {
        if (basisPoints == 0) return 0;
        return Math.addExact(Math.multiplyExact(value, basisPoints), 9_999) / 10_000;
    }

    private static long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) throw new SQLException("Database did not return a generated id");
            return keys.getLong(1);
        }
    }

    private static Instant instant(ResultSet results, String column) throws SQLException {
        long value = results.getLong(column);
        return results.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static boolean open(StockOrderStatus status) {
        return status == StockOrderStatus.OPEN || status == StockOrderStatus.PARTIAL;
    }

    private static boolean validId(String value, int maximum) {
        return value != null && value.matches("[a-zA-Z0-9][a-zA-Z0-9-]{0," + (maximum - 1) + "}");
    }

    private static boolean validName(String value, int maximum) {
        return value != null && !value.isBlank() && value.trim().length() <= maximum;
    }

    private static <T> StockOperation<T> rollback(Connection connection, StockResult result) throws SQLException {
        connection.rollback();
        return StockOperation.result(result);
    }

    private record BusinessRow(long id, String displayName, long balance, boolean active) { }
    private record ExchangeRow(long id, String displayName, long operatorBusinessId,
                               int feeBasisPoints, StockExchangeStatus status) { }
    private record ListingRow(long id, long exchangeId, long issuerBusinessId, String symbol,
                              long authorizedShares, long treasuryShares, StockListingStatus status,
                              long initialPriceCents) { }
    private record OrderRow(long id, long listingId, UUID ownerId, boolean issuerSale,
                            StockOrderSide side, long limitPriceCents, long originalQuantity,
                            long remainingQuantity, long escrowCents, StockOrderStatus status,
                            long createdAt) { }
    private record HolderRow(UUID playerId, String playerName, long quantity) { }
}
