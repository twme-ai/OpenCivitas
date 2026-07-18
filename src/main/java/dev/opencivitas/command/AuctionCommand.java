package dev.opencivitas.command;

import dev.opencivitas.auction.AuctionClaim;
import dev.opencivitas.auction.AuctionListing;
import dev.opencivitas.auction.AuctionOperation;
import dev.opencivitas.auction.AuctionRepository;
import dev.opencivitas.auction.AuctionResult;
import dev.opencivitas.database.Database;
import dev.opencivitas.economy.Money;
import dev.opencivitas.message.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class AuctionCommand implements CommandExecutor, TabCompleter, Listener {
    private static final int PAGE_SIZE = 45;

    private final JavaPlugin plugin;
    private final Database database;
    private final AuctionRepository auctions;
    private final MessageService messages;
    private final String currencySymbol;
    private final int defaultHours;
    private final int maximumHours;

    public AuctionCommand(
            JavaPlugin plugin,
            Database database,
            AuctionRepository auctions,
            MessageService messages,
            String currencySymbol,
            int defaultHours,
            int maximumHours
    ) {
        this.plugin = plugin;
        this.database = database;
        this.auctions = auctions;
        this.messages = messages;
        this.currencySymbol = currencySymbol;
        this.defaultHours = defaultHours;
        this.maximumHours = maximumHours;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length == 0 || args[0].equalsIgnoreCase("open") || args[0].equalsIgnoreCase("list")) {
            return browse(sender, args);
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "sell" -> sell(sender, args);
            case "bid" -> bid(sender, args);
            case "buy" -> buyout(sender, args);
            case "cancel" -> cancel(sender, args);
            case "claims" -> claims(sender, args);
            case "claim" -> claim(sender, args);
            case "mine" -> mine(sender, args);
            case "search" -> search(sender, args);
            case "help" -> {
                messages.send(sender, "auction.usage");
                yield true;
            }
            default -> {
                messages.send(sender, "auction.usage");
                yield true;
            }
        };
    }

    private boolean browse(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return true;
        }
        int index = args.length > 0 && (args[0].equalsIgnoreCase("list") || args[0].equalsIgnoreCase("open")) ? 1 : 0;
        Optional<Integer> page = page(sender, args.length > index ? args[index] : null);
        if (page.isEmpty()) {
            return true;
        }
        int offset;
        try {
            offset = Math.multiplyExact(page.get() - 1, PAGE_SIZE);
        } catch (ArithmeticException exception) {
            messages.send(sender, "error.invalid-page");
            return true;
        }
        complete(sender, database.submit(() -> auctions.active(PAGE_SIZE, offset)),
                listings -> openMenu(player, page.get(), listings));
        return true;
    }

    private boolean sell(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return true;
        }
        if (args.length < 2 || args.length > 4) {
            usage(sender, "/auction sell <starting-bid> [buyout|-] [hours]");
            return true;
        }
        Optional<Long> starting = money(sender, args[1]);
        if (starting.isEmpty()) {
            return true;
        }
        Long buyout = null;
        if (args.length >= 3 && !args[2].equals("-")) {
            Optional<Long> selected = money(sender, args[2]);
            if (selected.isEmpty()) {
                return true;
            }
            buyout = selected.get();
            if (buyout < starting.get()) {
                messages.send(sender, "auction.invalid-buyout");
                return true;
            }
        }
        int hours = defaultHours;
        if (args.length == 4) {
            try {
                hours = Integer.parseInt(args[3]);
                if (hours < 1 || hours > maximumHours) {
                    throw new NumberFormatException("Auction duration outside range");
                }
            } catch (NumberFormatException exception) {
                messages.send(sender, "auction.invalid-hours",
                        Placeholder.unparsed("maximum", Integer.toString(maximumHours)));
                return true;
            }
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            messages.send(sender, "auction.empty-hand");
            return true;
        }
        ItemStack escrow = held.clone();
        player.getInventory().setItemInMainHand(null);
        long now = System.currentTimeMillis();
        long endsAt = Math.addExact(now, Duration.ofHours(hours).toMillis());
        Long selectedBuyout = buyout;
        completeEscrow(player, escrow, database.submit(() -> auctions.create(
                player.getUniqueId(), escrow.serializeAsBytes(), escrow.getType().name(),
                itemName(escrow), escrow.getAmount(), starting.get(), selectedBuyout, now, endsAt)), operation -> {
            if (!succeeded(sender, operation)) {
                restore(player, escrow);
                return;
            }
            AuctionListing listing = operation.listing().orElseThrow();
            messages.send(sender, "auction.listed",
                    Placeholder.unparsed("id", Long.toString(listing.id())),
                    Placeholder.unparsed("item", listing.itemName()),
                    Placeholder.unparsed("price", Money.format(listing.startingBidCents(), currencySymbol)));
        });
        return true;
    }

    private boolean bid(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 3) {
            usage(sender, "/auction bid <id> <amount>");
            return true;
        }
        Optional<Long> id = id(sender, args[1]);
        Optional<Long> amount = money(sender, args[2]);
        if (id.isEmpty() || amount.isEmpty()) {
            return true;
        }
        complete(sender, database.submit(() -> auctions.bid(
                player.getUniqueId(), id.get(), amount.get(), System.currentTimeMillis())), operation -> {
            if (!succeeded(sender, operation)) {
                return;
            }
            messages.send(sender, "auction.bid-placed",
                    Placeholder.unparsed("id", Long.toString(id.get())),
                    Placeholder.unparsed("amount", Money.format(operation.amountCents(), currencySymbol)),
                    Placeholder.unparsed("balance", Money.format(operation.balanceCents(), currencySymbol)));
        });
        return true;
    }

    private boolean buyout(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 2) {
            usage(sender, "/auction buy <id>");
            return true;
        }
        Optional<Long> id = id(sender, args[1]);
        if (id.isEmpty()) {
            return true;
        }
        complete(sender, database.submit(() -> auctions.buyout(
                player.getUniqueId(), id.get(), System.currentTimeMillis())), operation -> {
            if (!succeeded(sender, operation)) {
                return;
            }
            messages.send(sender, "auction.bought",
                    Placeholder.unparsed("id", Long.toString(id.get())),
                    Placeholder.unparsed("price", Money.format(operation.amountCents(), currencySymbol)),
                    Placeholder.unparsed("balance", Money.format(operation.balanceCents(), currencySymbol)));
            messages.send(sender, "auction.claim-reminder");
        });
        return true;
    }

    private boolean cancel(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 2) {
            usage(sender, "/auction cancel <id>");
            return true;
        }
        Optional<Long> id = id(sender, args[1]);
        if (id.isEmpty()) {
            return true;
        }
        complete(sender, database.submit(() -> auctions.cancel(
                player.getUniqueId(), id.get(), System.currentTimeMillis())), operation -> {
            if (!succeeded(sender, operation)) {
                return;
            }
            messages.send(sender, "auction.cancelled", Placeholder.unparsed("id", Long.toString(id.get())));
            messages.send(sender, "auction.claim-reminder");
        });
        return true;
    }

    private boolean claims(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 1) {
            usage(sender, "/auction claims");
            return true;
        }
        complete(sender, database.submit(() -> auctions.claims(player.getUniqueId())), pending -> {
            messages.send(sender, "auction.claims-header");
            if (pending.isEmpty()) {
                messages.send(sender, "auction.claims-empty");
            }
            for (AuctionClaim claim : pending) {
                messages.send(sender, "auction.claim-entry",
                        Placeholder.unparsed("id", Long.toString(claim.id())),
                        Placeholder.unparsed("item", claim.itemName()),
                        Placeholder.unparsed("quantity", Integer.toString(claim.itemQuantity())));
            }
        });
        return true;
    }

    private boolean claim(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 2) {
            usage(sender, "/auction claim <claim-id>");
            return true;
        }
        Optional<Long> id = id(sender, args[1]);
        if (id.isEmpty()) {
            return true;
        }
        complete(sender, database.submit(() -> auctions.claim(
                player.getUniqueId(), id.get(), System.currentTimeMillis())), selected -> {
            if (selected.isEmpty()) {
                messages.send(sender, "auction.claim-not-found");
                return;
            }
            ItemStack item = ItemStack.deserializeBytes(selected.get().itemData());
            restore(player, item);
            messages.send(sender, "auction.claimed", Placeholder.unparsed("item", selected.get().itemName()));
        });
        return true;
    }

    private boolean mine(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 1) {
            usage(sender, "/auction mine");
            return true;
        }
        complete(sender, database.submit(() -> auctions.bySeller(player.getUniqueId(), 50)),
                listings -> showListings(sender, listings));
        return true;
    }

    private boolean search(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender, "/auction search <item>");
            return true;
        }
        String query = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        complete(sender, database.submit(() -> auctions.search(query, 50)),
                listings -> showListings(sender, listings));
        return true;
    }

    private void openMenu(Player player, int page, List<AuctionListing> listings) {
        AuctionMenu menu = new AuctionMenu(messages.component(player, "auction.menu-title",
                Placeholder.unparsed("page", Integer.toString(page))));
        int slot = 0;
        for (AuctionListing listing : listings) {
            ItemStack display = ItemStack.deserializeBytes(listing.itemData());
            ItemMeta meta = display.getItemMeta();
            List<Component> lore = new ArrayList<>(Optional.ofNullable(meta.lore()).orElse(List.of()));
            lore.add(messages.component(player, "auction.menu-seller",
                    Placeholder.unparsed("player", listing.sellerName())));
            lore.add(messages.component(player, "auction.menu-bid",
                    Placeholder.unparsed("amount", Money.format(
                            listing.currentBidCents() == 0 ? listing.startingBidCents() : listing.currentBidCents(),
                            currencySymbol))));
            lore.add(messages.component(player, "auction.menu-buyout",
                    Placeholder.unparsed("amount", listing.buyoutCents() == null
                            ? "-" : Money.format(listing.buyoutCents(), currencySymbol))));
            lore.add(messages.component(player, "auction.menu-id",
                    Placeholder.unparsed("id", Long.toString(listing.id()))));
            meta.lore(lore);
            display.setItemMeta(meta);
            menu.inventory().setItem(slot++, display);
        }
        player.openInventory(menu.inventory());
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof AuctionMenu)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof AuctionMenu) {
            event.setCancelled(true);
        }
    }

    private void showListings(CommandSender sender, List<AuctionListing> listings) {
        messages.send(sender, "auction.list-header");
        if (listings.isEmpty()) {
            messages.send(sender, "auction.list-empty");
        }
        for (AuctionListing listing : listings) {
            messages.send(sender, "auction.list-entry",
                    Placeholder.unparsed("id", Long.toString(listing.id())),
                    Placeholder.unparsed("item", listing.itemName()),
                    Placeholder.unparsed("quantity", Integer.toString(listing.itemQuantity())),
                    Placeholder.unparsed("seller", listing.sellerName()),
                    Placeholder.unparsed("bid", Money.format(
                            listing.currentBidCents() == 0 ? listing.startingBidCents() : listing.currentBidCents(),
                            currencySymbol)),
                    Placeholder.unparsed("state", listing.state().name().toLowerCase(Locale.ROOT)));
        }
    }

    private boolean succeeded(CommandSender sender, AuctionOperation operation) {
        if (operation.result() == AuctionResult.SUCCESS) {
            return true;
        }
        String key = switch (operation.result()) {
            case LISTING_NOT_FOUND -> "auction.not-found";
            case LISTING_INACTIVE -> "auction.inactive";
            case LISTING_EXPIRED -> "auction.expired";
            case LISTING_LIMIT -> "auction.limit";
            case INVALID_BID -> "auction.invalid-bid";
            case BUYOUT_UNAVAILABLE -> "auction.no-buyout";
            case BID_EXISTS -> "auction.has-bid";
            case NO_PERMISSION -> "auction.no-permission";
            case INSUFFICIENT_FUNDS -> "auction.insufficient-funds";
            case CITIZEN_NOT_FOUND -> "auction.citizen-not-found";
            case SELF -> "auction.self";
            default -> "auction.failed";
        };
        messages.send(sender, key);
        return false;
    }

    private static String itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta.hasDisplayName()) {
            return PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }
        return item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private void restore(Player player, ItemStack item) {
        for (ItemStack overflow : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }

    private Optional<Long> id(CommandSender sender, String input) {
        try {
            long id = Long.parseLong(input);
            if (id < 1) {
                throw new NumberFormatException("ID must be positive");
            }
            return Optional.of(id);
        } catch (NumberFormatException exception) {
            messages.send(sender, "auction.invalid-id");
            return Optional.empty();
        }
    }

    private Optional<Long> money(CommandSender sender, String input) {
        try {
            return Optional.of(Money.parsePositiveCents(input));
        } catch (IllegalArgumentException exception) {
            messages.send(sender, "error.invalid-amount");
            return Optional.empty();
        }
    }

    private Optional<Integer> page(CommandSender sender, String input) {
        try {
            int page = input == null ? 1 : Integer.parseInt(input);
            if (page < 1) {
                throw new NumberFormatException("Page must be positive");
            }
            return Optional.of(page);
        } catch (NumberFormatException | ArithmeticException exception) {
            messages.send(sender, "error.invalid-page");
            return Optional.empty();
        }
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        messages.send(sender, "error.player-only");
        return null;
    }

    private void usage(CommandSender sender, String usage) {
        messages.send(sender, "error.usage", Placeholder.unparsed("usage", usage));
    }

    private <T> void complete(CommandSender sender, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "An asynchronous auction operation failed", error);
                messages.send(sender, "error.database");
            } else {
                success.accept(result);
            }
        }));
    }

    private void completeEscrow(
            Player player,
            ItemStack escrow,
            CompletableFuture<AuctionOperation> future,
            Consumer<AuctionOperation> success
    ) {
        future.whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                restore(player, escrow);
                plugin.getLogger().log(Level.SEVERE, "Could not create auction listing", error);
                messages.send(player, "error.database");
            } else {
                success.accept(result);
            }
        }));
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (args.length == 1) {
            return filter(args[0], List.of(
                    "open", "list", "sell", "bid", "buy", "cancel", "claims", "claim", "mine", "search", "help"));
        }
        return List.of();
    }

    private static List<String> filter(String input, Collection<String> candidates) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return candidates.stream().filter(value -> value.startsWith(prefix)).toList();
    }

    private static final class AuctionMenu implements InventoryHolder {
        private final Inventory inventory;

        private AuctionMenu(Component title) {
            inventory = Bukkit.createInventory(this, 54, title);
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }

        private Inventory inventory() {
            return inventory;
        }
    }
}
