package dev.opencivitas.command;

import dev.opencivitas.citizen.CitizenProfile;
import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.economy.Money;
import dev.opencivitas.message.MessageService;
import dev.opencivitas.stock.StockDividend;
import dev.opencivitas.stock.StockExchange;
import dev.opencivitas.stock.StockListing;
import dev.opencivitas.stock.StockOperation;
import dev.opencivitas.stock.StockOrder;
import dev.opencivitas.stock.StockOrderPlacement;
import dev.opencivitas.stock.StockOrderSide;
import dev.opencivitas.stock.StockPolicy;
import dev.opencivitas.stock.StockPosition;
import dev.opencivitas.stock.StockQuote;
import dev.opencivitas.stock.StockRepository;
import dev.opencivitas.stock.StockResult;
import dev.opencivitas.stock.StockShareholder;
import dev.opencivitas.stock.StockTrade;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class StockCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    private static final List<String> ACTIONS = List.of(
            "exchanges", "listings", "quote", "portfolio", "orders", "buy", "sell",
            "cancel", "trades", "holders", "apply", "review", "dividend", "halt", "resume", "exchange");

    private final JavaPlugin plugin;
    private final Database database;
    private final CitizenRepository citizens;
    private final StockRepository stocks;
    private final StockPolicy policy;
    private final MessageService messages;
    private final String currencySymbol;

    public StockCommand(
            JavaPlugin plugin,
            Database database,
            CitizenRepository citizens,
            StockRepository stocks,
            StockPolicy policy,
            MessageService messages,
            String currencySymbol
    ) {
        this.plugin = plugin;
        this.database = database;
        this.citizens = citizens;
        this.stocks = stocks;
        this.policy = policy;
        this.messages = messages;
        this.currencySymbol = currencySymbol;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (label.equalsIgnoreCase("exchange")) return exchange(sender, args);
        if (args.length == 0) return listings(sender, args);
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "exchanges" -> exchanges(sender, args);
            case "listings" -> listings(sender, Arrays.copyOfRange(args, 1, args.length));
            case "quote" -> quote(sender, args);
            case "portfolio" -> portfolio(sender, args);
            case "orders" -> orders(sender, args);
            case "buy" -> order(sender, args, StockOrderSide.BUY);
            case "sell" -> order(sender, args, StockOrderSide.SELL);
            case "cancel" -> cancel(sender, args);
            case "trades" -> trades(sender, args);
            case "holders" -> holders(sender, args);
            case "apply" -> apply(sender, args);
            case "review" -> review(sender, args);
            case "dividend" -> dividend(sender, args);
            case "halt" -> halt(sender, args, true);
            case "resume" -> halt(sender, args, false);
            case "exchange" -> exchange(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> usage(sender, "/stock <listings|quote|portfolio|orders|buy|sell|cancel|trades|holders>");
        };
    }

    private boolean exchanges(CommandSender sender, String[] args) {
        if (args.length != 1) return usage(sender, "/stock exchanges");
        complete(sender, database.submit(stocks::exchanges), exchanges -> {
            messages.send(sender, "stock.exchanges-header");
            if (exchanges.isEmpty()) messages.send(sender, "stock.exchanges-empty");
            for (StockExchange exchange : exchanges) messages.send(sender, "stock.exchange-entry",
                    Placeholder.unparsed("exchange", exchange.displayName()),
                    Placeholder.unparsed("slug", exchange.slug()),
                    Placeholder.unparsed("business", exchange.operatorBusiness()),
                    Placeholder.unparsed("fee", fee(exchange.feeBasisPoints())),
                    Placeholder.unparsed("status", exchange.status().name().toLowerCase(Locale.ROOT)));
        });
        return true;
    }

    private boolean listings(CommandSender sender, String[] args) {
        if (args.length != 0) return usage(sender, "/stock listings");
        complete(sender, database.submit(stocks::listings), listings -> {
            messages.send(sender, "stock.listings-header");
            if (listings.isEmpty()) messages.send(sender, "stock.listings-empty");
            for (StockListing listing : listings) messages.send(sender, "stock.listing-entry",
                    Placeholder.unparsed("symbol", listing.symbol()),
                    Placeholder.unparsed("business", listing.issuerBusiness()),
                    Placeholder.unparsed("exchange", listing.exchangeName()),
                    Placeholder.unparsed("price", money(listing.lastPriceCents())),
                    Placeholder.unparsed("status", listing.status().name().toLowerCase(Locale.ROOT)));
        });
        return true;
    }

    private boolean quote(CommandSender sender, String[] args) {
        if (args.length != 2) return usage(sender, "/stock quote <symbol>");
        complete(sender, database.submit(() -> stocks.quote(args[1])), found -> {
            if (found.isEmpty()) {
                messages.send(sender, "stock.listing-not-found");
                return;
            }
            StockQuote quote = found.orElseThrow();
            StockListing listing = quote.listing();
            messages.send(sender, "stock.quote",
                    Placeholder.unparsed("symbol", listing.symbol()),
                    Placeholder.unparsed("business", listing.issuerBusiness()),
                    Placeholder.unparsed("exchange", listing.exchangeName()),
                    Placeholder.unparsed("last", money(listing.lastPriceCents())),
                    Placeholder.unparsed("bid", optionalMoney(quote.bestBidCents())),
                    Placeholder.unparsed("ask", optionalMoney(quote.bestAskCents())),
                    Placeholder.unparsed("shares", Long.toString(listing.authorizedShares())),
                    Placeholder.unparsed("holders", Integer.toString(quote.shareholderCount())),
                    Placeholder.unparsed("status", listing.status().name().toLowerCase(Locale.ROOT)),
                    Placeholder.unparsed("updated", listing.lastTradeAt() == null
                            ? DATE.format(listing.appliedAt()) : DATE.format(listing.lastTradeAt())));
        });
        return true;
    }

    private boolean portfolio(CommandSender sender, String[] args) {
        if (args.length > 2) return usage(sender, "/stock portfolio [player]");
        if (!(sender instanceof Player) && args.length == 1) return usage(sender, "/stock portfolio <player>");
        complete(sender, database.submit(() -> {
            CitizenProfile target;
            if (args.length == 2) target = citizens.findByName(args[1]).orElse(null);
            else target = citizens.find(((Player) sender).getUniqueId()).orElse(null);
            return target == null ? new PortfolioResult(null, List.of())
                    : new PortfolioResult(target, stocks.portfolio(target.uuid()));
        }), result -> {
            if (result.target() == null) {
                messages.send(sender, "stock.citizen-not-found");
                return;
            }
            messages.send(sender, "stock.portfolio-header",
                    Placeholder.unparsed("player", result.target().lastName()));
            if (result.positions().isEmpty()) messages.send(sender, "stock.portfolio-empty");
            for (StockPosition position : result.positions()) {
                long value;
                try {
                    value = Math.multiplyExact(position.totalQuantity(), position.lastPriceCents());
                } catch (ArithmeticException exception) {
                    value = Long.MAX_VALUE;
                }
                messages.send(sender, "stock.position-entry",
                        Placeholder.unparsed("symbol", position.symbol()),
                        Placeholder.unparsed("business", position.issuerBusiness()),
                        Placeholder.unparsed("quantity", Long.toString(position.totalQuantity())),
                        Placeholder.unparsed("escrow", Long.toString(position.saleEscrowQuantity())),
                        Placeholder.unparsed("value", money(value)));
            }
        });
        return true;
    }

    private boolean orders(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length > 2 || args.length == 2 && !args[1].equalsIgnoreCase("all")) {
            return usage(sender, "/stock orders [all]");
        }
        boolean openOnly = args.length == 1;
        complete(sender, database.submit(() -> stocks.orders(player.getUniqueId(), openOnly)), orders -> {
            messages.send(sender, "stock.orders-header");
            if (orders.isEmpty()) messages.send(sender, "stock.orders-empty");
            for (StockOrder order : orders) messages.send(sender, "stock.order-entry",
                    Placeholder.unparsed("id", Long.toString(order.id())),
                    Placeholder.unparsed("side", order.side().name().toLowerCase(Locale.ROOT)),
                    Placeholder.unparsed("symbol", order.symbol()),
                    Placeholder.unparsed("remaining", Long.toString(order.remainingQuantity())),
                    Placeholder.unparsed("original", Long.toString(order.originalQuantity())),
                    Placeholder.unparsed("price", money(order.limitPriceCents())),
                    Placeholder.unparsed("status", order.status().name().toLowerCase(Locale.ROOT)));
        });
        return true;
    }

    private boolean order(CommandSender sender, String[] args, StockOrderSide side) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 4) return usage(sender,
                "/stock " + side.name().toLowerCase(Locale.ROOT) + " <symbol> <quantity> <limit-price>");
        Long quantity = positiveLong(args[2]);
        Long price = price(args[3]);
        if (quantity == null || price == null) return invalidValue(sender);
        complete(sender, database.submit(() -> stocks.placeOrder(
                player.getUniqueId(), args[1], side, quantity, price,
                policy.maximumOpenOrders(), policy.maximumOrderQuantity(), System.currentTimeMillis())), operation -> {
            if (!success(sender, operation)) return;
            StockOrderPlacement placement = operation.value();
            messages.send(sender, "stock.order-placed",
                    Placeholder.unparsed("id", Long.toString(placement.order().id())),
                    Placeholder.unparsed("status", placement.order().status().name().toLowerCase(Locale.ROOT)),
                    Placeholder.unparsed("remaining", Long.toString(placement.order().remainingQuantity())),
                    Placeholder.unparsed("trades", Integer.toString(placement.trades().size())));
        });
        return true;
    }

    private boolean cancel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 2) return usage(sender, "/stock cancel <order-id>");
        Long id = positiveLong(args[1]);
        if (id == null) return invalidValue(sender);
        complete(sender, database.submit(() -> stocks.cancelOrder(
                player.getUniqueId(), id, System.currentTimeMillis())), operation -> {
            if (success(sender, operation)) messages.send(sender, "stock.order-cancelled",
                    Placeholder.unparsed("id", Long.toString(id)));
        });
        return true;
    }

    private boolean trades(CommandSender sender, String[] args) {
        if (args.length != 2) return usage(sender, "/stock trades <symbol>");
        complete(sender, database.submit(() -> stocks.trades(args[1], 20)), trades -> {
            messages.send(sender, "stock.trades-header", Placeholder.unparsed("symbol", args[1].toUpperCase(Locale.ROOT)));
            if (trades.isEmpty()) messages.send(sender, "stock.trades-empty");
            for (StockTrade trade : trades) messages.send(sender, "stock.trade-entry",
                    Placeholder.unparsed("date", DATE.format(trade.executedAt())),
                    Placeholder.unparsed("quantity", Long.toString(trade.quantity())),
                    Placeholder.unparsed("price", money(trade.priceCents())),
                    Placeholder.unparsed("buyer", trade.buyerName()),
                    Placeholder.unparsed("seller", Optional.ofNullable(trade.sellerName())
                            .orElse(trade.sellerBusiness())));
        });
        return true;
    }

    private boolean holders(CommandSender sender, String[] args) {
        if (args.length != 2) return usage(sender, "/stock holders <symbol>");
        complete(sender, database.submit(() -> {
            StockListing listing = stocks.findListing(args[1]).orElse(null);
            return new HolderResult(listing, listing == null ? List.of() : stocks.shareholders(args[1]));
        }), result -> {
            if (result.listing() == null) {
                messages.send(sender, "stock.listing-not-found");
                return;
            }
            messages.send(sender, "stock.holders-header", Placeholder.unparsed("symbol", result.listing().symbol()));
            if (result.holders().isEmpty()) messages.send(sender, "stock.holders-empty");
            for (StockShareholder holder : result.holders()) messages.send(sender, "stock.holder-entry",
                    Placeholder.unparsed("player", holder.playerName()),
                    Placeholder.unparsed("quantity", Long.toString(holder.quantity())),
                    Placeholder.unparsed("percent", percent(holder.quantity(), result.listing().authorizedShares())));
        });
        return true;
    }

    private boolean apply(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 6) return usage(sender,
                "/stock apply <exchange> <business> <symbol> <shares> <initial-price>");
        Long shares = positiveLong(args[4]);
        Long initialPrice = price(args[5]);
        if (shares == null || initialPrice == null) return invalidValue(sender);
        complete(sender, database.submit(() -> stocks.applyListing(
                player.getUniqueId(), args[1], args[2], args[3], shares, initialPrice,
                policy.maximumListingShares(), System.currentTimeMillis())), operation -> {
            if (success(sender, operation)) messages.send(sender, "stock.listing-applied",
                    Placeholder.unparsed("symbol", operation.value().symbol()));
        });
        return true;
    }

    private boolean review(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 3 || !List.of("approve", "reject").contains(args[2].toLowerCase(Locale.ROOT))) {
            return usage(sender, "/stock review <symbol> <approve|reject>");
        }
        boolean approve = args[2].equalsIgnoreCase("approve");
        complete(sender, database.submit(() -> stocks.reviewListing(
                player.getUniqueId(), args[1], approve, System.currentTimeMillis())), operation -> {
            if (success(sender, operation)) messages.send(sender,
                    approve ? "stock.listing-approved" : "stock.listing-rejected",
                    Placeholder.unparsed("symbol", operation.value().symbol()));
        });
        return true;
    }

    private boolean dividend(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 3) return usage(sender, "/stock dividend <symbol> <per-share>");
        Long amount = price(args[2]);
        if (amount == null) return invalidValue(sender);
        complete(sender, database.submit(() -> stocks.payDividend(
                player.getUniqueId(), args[1], amount, System.currentTimeMillis())), operation -> {
            if (!success(sender, operation)) return;
            StockDividend dividend = operation.value();
            messages.send(sender, "stock.dividend-paid",
                    Placeholder.unparsed("symbol", dividend.symbol()),
                    Placeholder.unparsed("per-share", money(dividend.perShareCents())),
                    Placeholder.unparsed("total", money(dividend.totalCents())),
                    Placeholder.unparsed("recipients", Integer.toString(dividend.recipientCount())));
        });
        return true;
    }

    private boolean halt(CommandSender sender, String[] args, boolean halted) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 2) return usage(sender, "/stock " + (halted ? "halt" : "resume") + " <symbol>");
        complete(sender, database.submit(() -> stocks.setListingHalted(
                player.getUniqueId(), args[1], halted,
                sender.hasPermission("opencivitas.stocks.manage"), System.currentTimeMillis())), operation -> {
            if (success(sender, operation)) messages.send(sender,
                    halted ? "stock.listing-halted" : "stock.listing-resumed",
                    Placeholder.unparsed("symbol", operation.value().symbol()));
        });
        return true;
    }

    private boolean exchange(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            return exchanges(sender, new String[]{"exchanges"});
        }
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length < 4 || !args[0].equalsIgnoreCase("create")) {
            return usage(sender, "/exchange create <business> <slug> [fee-percent] <display name>");
        }
        Integer parsedFee = basisPoints(args[3]);
        int fee = parsedFee == null ? policy.defaultFeeBasisPoints() : parsedFee;
        int displayStart = parsedFee == null ? 3 : 4;
        if (args.length <= displayStart) return usage(sender,
                "/exchange create <business> <slug> [fee-percent] <display name>");
        String displayName = String.join(" ", Arrays.copyOfRange(args, displayStart, args.length));
        complete(sender, database.submit(() -> stocks.createExchange(
                player.getUniqueId(), args[1], args[2], displayName, fee,
                policy.maximumFeeBasisPoints(), System.currentTimeMillis())), operation -> {
            if (success(sender, operation)) messages.send(sender, "stock.exchange-created",
                    Placeholder.unparsed("exchange", operation.value().displayName()));
        });
        return true;
    }

    private boolean success(CommandSender sender, StockOperation<?> operation) {
        if (operation.result() == StockResult.SUCCESS) return true;
        messages.send(sender, switch (operation.result()) {
            case CITIZEN_NOT_FOUND -> "stock.citizen-not-found";
            case BUSINESS_NOT_FOUND -> "stock.business-not-found";
            case BUSINESS_INACTIVE -> "stock.business-inactive";
            case EXCHANGE_NOT_FOUND -> "stock.exchange-not-found";
            case EXCHANGE_INACTIVE -> "stock.exchange-inactive";
            case EXCHANGE_EXISTS -> "stock.exchange-exists";
            case LISTING_NOT_FOUND -> "stock.listing-not-found";
            case LISTING_EXISTS -> "stock.listing-exists";
            case LISTING_PENDING -> "stock.listing-pending";
            case LISTING_INACTIVE -> "stock.listing-inactive";
            case ORDER_NOT_FOUND -> "stock.order-not-found";
            case ORDER_NOT_OPEN -> "stock.order-not-open";
            case OPEN_ORDER_LIMIT -> "stock.order-limit";
            case INSUFFICIENT_FUNDS -> "stock.insufficient-funds";
            case INSUFFICIENT_SHARES -> "stock.insufficient-shares";
            case INSUFFICIENT_BUSINESS_FUNDS -> "stock.insufficient-business-funds";
            case NO_SHAREHOLDERS -> "stock.no-shareholders";
            case NO_PERMISSION -> "stock.no-permission";
            case INVALID_VALUE -> "stock.invalid-value";
            case SUCCESS -> throw new IllegalStateException();
        });
        return false;
    }

    private <T> void complete(CommandSender sender, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((result, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Stock command failed", error);
                    messages.send(sender, "error.database");
                } else {
                    success.accept(result);
                }
            });
        });
    }

    private Long price(String input) {
        try {
            return Money.parsePositiveCents(input);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Long positiveLong(String input) {
        try {
            long value = Long.parseLong(input);
            return value > 0 ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Integer basisPoints(String input) {
        try {
            BigDecimal percent = new BigDecimal(input);
            int basisPoints = percent.movePointRight(2).intValueExact();
            return basisPoints >= 0 && basisPoints <= policy.maximumFeeBasisPoints() ? basisPoints : null;
        } catch (NumberFormatException | ArithmeticException exception) {
            return null;
        }
    }

    private String money(long cents) {
        return Money.format(cents, currencySymbol);
    }

    private String optionalMoney(Long cents) {
        return cents == null ? "-" : money(cents);
    }

    private static String fee(int basisPoints) {
        return BigDecimal.valueOf(basisPoints, 2).stripTrailingZeros().toPlainString() + "%";
    }

    private static String percent(long quantity, long total) {
        if (total <= 0) return "0%";
        return BigDecimal.valueOf(quantity).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString() + "%";
    }

    private boolean invalidValue(CommandSender sender) {
        messages.send(sender, "stock.invalid-value");
        return true;
    }

    private boolean playerOnly(CommandSender sender) {
        messages.send(sender, "error.player-only");
        return true;
    }

    private boolean usage(CommandSender sender, String usage) {
        messages.send(sender, "error.usage", Placeholder.unparsed("usage", usage));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (alias.equalsIgnoreCase("exchange")) {
            if (args.length == 1) return filter(List.of("list", "create"), args[0]);
            return List.of();
        }
        if (args.length == 1) return filter(ACTIONS, args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("portfolio")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("review")) {
            return filter(List.of("approve", "reject"), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> values, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private record PortfolioResult(CitizenProfile target, List<StockPosition> positions) { }
    private record HolderResult(StockListing listing, List<StockShareholder> holders) { }
}
