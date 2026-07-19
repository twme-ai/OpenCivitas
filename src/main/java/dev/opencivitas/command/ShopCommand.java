package dev.opencivitas.command;

import dev.opencivitas.database.Database;
import dev.opencivitas.economy.Money;
import dev.opencivitas.message.MessageService;
import dev.opencivitas.shop.ChestShop;
import dev.opencivitas.shop.ShopHologramService;
import dev.opencivitas.shop.ShopRepository;
import dev.opencivitas.shop.ShopResult;
import dev.opencivitas.shop.ShopSale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class ShopCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);
    private static final int FIND_LIMIT = 20;

    private final JavaPlugin plugin;
    private final Database database;
    private final ShopRepository shops;
    private final ShopHologramService holograms;
    private final MessageService messages;
    private final String currencySymbol;
    private final int pageSize;

    public ShopCommand(
            JavaPlugin plugin,
            Database database,
            ShopRepository shops,
            ShopHologramService holograms,
            MessageService messages,
            String currencySymbol,
            int pageSize
    ) {
        this.plugin = plugin;
        this.database = database;
        this.shops = shops;
        this.holograms = holograms;
        this.messages = messages;
        this.currencySymbol = currencySymbol;
        this.pageSize = pageSize;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (command.getName().equalsIgnoreCase("find")) {
            return find(sender, args);
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("history")) {
            return history(sender, args);
        }
        messages.send(sender, "shops.command.usage");
        return true;
    }

    private boolean find(CommandSender sender, String[] args) {
        if (args.length != 1) {
            usage(sender, "/find <item|toggle>");
            return true;
        }
        if (args[0].equalsIgnoreCase("toggle")) {
            if (!(sender instanceof Player player)) {
                messages.send(sender, "error.player-only");
                return true;
            }
            complete(sender, database.submit(() -> shops.toggleHolograms(
                    player.getUniqueId(), Instant.now().toEpochMilli())), setting -> {
                if (setting.result() != ShopResult.SUCCESS) {
                    messages.send(sender, "error.database");
                    return;
                }
                holograms.setVisible(player, setting.visible());
                messages.send(player, setting.visible()
                        ? "shops.find.holograms-enabled" : "shops.find.holograms-disabled");
            });
            return true;
        }
        Material material = Material.matchMaterial(args[0].replace(' ', '_'));
        if (material == null || !material.isItem() || material.isAir()) {
            messages.send(sender, "shops.command.invalid-item",
                    Placeholder.unparsed("item", args[0]));
            return true;
        }
        complete(sender, database.submit(() -> shops.search(material.name(), FIND_LIMIT)), found -> {
            messages.send(sender, "shops.find.header",
                    Placeholder.unparsed("item", displayItem(material.name())));
            if (found.isEmpty()) {
                messages.send(sender, "shops.find.empty");
                return;
            }
            for (ChestShop shop : found) {
                messages.send(sender, "shops.find.entry",
                        Placeholder.unparsed("account", shop.accountName()),
                        Placeholder.unparsed("world", shop.worldName()),
                        Placeholder.unparsed("x", Integer.toString(shop.signX())),
                        Placeholder.unparsed("y", Integer.toString(shop.signY())),
                        Placeholder.unparsed("z", Integer.toString(shop.signZ())),
                        Placeholder.unparsed("prices", prices(shop)));
            }
        });
        return true;
    }

    private boolean history(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length > 2) {
            usage(sender, "/csn history [page]");
            return true;
        }
        Optional<Integer> page = page(sender, args.length == 2 ? args[1] : null);
        if (page.isEmpty()) {
            return true;
        }
        int offset;
        try {
            offset = Math.multiplyExact(page.get() - 1, pageSize);
        } catch (ArithmeticException exception) {
            messages.send(sender, "error.invalid-page");
            return true;
        }
        complete(sender,
                database.submit(() -> shops.history(player.getUniqueId(), pageSize, offset)),
                sales -> showHistory(sender, page.get(), sales));
        return true;
    }

    private void showHistory(CommandSender sender, int page, List<ShopSale> sales) {
        messages.send(sender, "shops.history.header",
                Placeholder.unparsed("page", Integer.toString(page)));
        if (sales.isEmpty()) {
            messages.send(sender, "shops.history.empty");
            return;
        }
        for (ShopSale sale : sales) {
            messages.send(sender, sale.direction() == dev.opencivitas.shop.ShopDirection.BUY
                            ? "shops.history.entry-buy" : "shops.history.entry-sell",
                    Placeholder.unparsed("date", DATE.format(sale.createdAt())),
                    Placeholder.unparsed("player", sale.customerName()),
                    Placeholder.unparsed("amount", Integer.toString(sale.itemAmount())),
                    Placeholder.unparsed("item", displayItem(sale.itemKey())),
                    Placeholder.unparsed("price", Money.format(sale.totalCents(), currencySymbol)),
                    Placeholder.unparsed("account", sale.accountName()));
        }
    }

    private String prices(ChestShop shop) {
        String buy = shop.buyPriceCents() == null ? "-"
                : Money.format(shop.buyPriceCents(), currencySymbol);
        String sell = shop.sellPriceCents() == null ? "-"
                : Money.format(shop.sellPriceCents(), currencySymbol);
        return "B " + buy + " / S " + sell;
    }

    private Optional<Integer> page(CommandSender sender, String input) {
        if (input == null) {
            return Optional.of(1);
        }
        try {
            int page = Integer.parseInt(input);
            if (page < 1) {
                throw new NumberFormatException("Page must be positive");
            }
            return Optional.of(page);
        } catch (NumberFormatException exception) {
            messages.send(sender, "error.invalid-page");
            return Optional.empty();
        }
    }

    private void usage(CommandSender sender, String value) {
        messages.send(sender, "error.usage", Placeholder.unparsed("usage", value));
    }

    private <T> void complete(CommandSender sender, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "An asynchronous shop operation failed", error);
                messages.send(sender, "error.database");
                return;
            }
            success.accept(result);
        }));
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (command.getName().equalsIgnoreCase("chestshop") && args.length == 1
                && "history".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("history");
        }
        if (command.getName().equalsIgnoreCase("find") && args.length == 1
                && "toggle".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("toggle");
        }
        return List.of();
    }

    private static String displayItem(String itemKey) {
        return itemKey.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
