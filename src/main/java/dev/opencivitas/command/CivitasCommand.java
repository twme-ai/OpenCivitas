package dev.opencivitas.command;

import dev.opencivitas.citizen.CitizenProfile;
import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.economy.LedgerEntry;
import dev.opencivitas.economy.LedgerEntryType;
import dev.opencivitas.economy.Money;
import dev.opencivitas.economy.TransferResult;
import dev.opencivitas.locale.LocaleResolver;
import dev.opencivitas.message.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class CivitasCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final JavaPlugin plugin;
    private final Database database;
    private final CitizenRepository citizens;
    private final MessageService messages;
    private final String currencySymbol;
    private final int transactionPageSize;

    public CivitasCommand(
            JavaPlugin plugin,
            Database database,
            CitizenRepository citizens,
            MessageService messages,
            String currencySymbol,
            int transactionPageSize
    ) {
        this.plugin = plugin;
        this.database = database;
        this.citizens = citizens;
        this.messages = messages;
        this.currencySymbol = currencySymbol;
        this.transactionPageSize = transactionPageSize;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "balance" -> balance(sender, args);
            case "pay" -> pay(sender, args);
            case "transactions" -> transactions(sender, args);
            case "about" -> about(sender, args);
            case "locale" -> locale(sender, args);
            default -> false;
        };
    }

    private boolean balance(CommandSender sender, String[] args) {
        if (args.length > 1) {
            usage(sender, "/balance [player]");
            return true;
        }
        if (args.length == 0 && !(sender instanceof Player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length == 1 && !sender.hasPermission("opencivitas.balance.others")) {
            messages.send(sender, "error.no-permission");
            return true;
        }

        CompletableFuture<Optional<CitizenProfile>> request = args.length == 0
                ? database.submit(() -> citizens.find(((Player) sender).getUniqueId()))
                : database.submit(() -> citizens.findByName(args[0]));
        complete(sender, request, profile -> {
            if (profile.isEmpty()) {
                messages.send(sender, "error.player-not-found",
                        Placeholder.unparsed("player", args.length == 0 ? sender.getName() : args[0]));
                return;
            }
            CitizenProfile citizen = profile.get();
            String key = args.length == 0 ? "balance.self" : "balance.other";
            messages.send(sender, key,
                    Placeholder.unparsed("player", citizen.lastName()),
                    Placeholder.unparsed("amount", Money.format(citizen.balanceCents(), currencySymbol)));
        });
        return true;
    }

    private boolean pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 2) {
            usage(sender, "/pay <player> <amount>");
            return true;
        }

        long amount;
        try {
            amount = Money.parsePositiveCents(args[1]);
        } catch (IllegalArgumentException exception) {
            messages.send(sender, "error.invalid-amount");
            return true;
        }

        database.submit(() -> citizens.findByName(args[0])).whenComplete((target, lookupError) -> {
            if (lookupError != null) {
                Bukkit.getScheduler().runTask(plugin, () -> scheduleError(sender, lookupError));
                return;
            }
            if (target.isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> messages.send(sender, "error.player-not-found",
                        Placeholder.unparsed("player", args[0])));
                return;
            }
            CitizenProfile recipient = target.get();
            if (recipient.uuid().equals(player.getUniqueId())) {
                Bukkit.getScheduler().runTask(plugin, () -> messages.send(sender, "error.self-payment"));
                return;
            }
            complete(sender,
                    database.submit(() -> citizens.transfer(player.getUniqueId(), recipient.uuid(), amount)),
                    result -> finishPayment(player, recipient, amount, result));
        });
        return true;
    }

    private void finishPayment(Player sender, CitizenProfile recipient, long amount, TransferResult result) {
        switch (result.status()) {
            case INSUFFICIENT_FUNDS -> messages.send(sender, "error.insufficient-funds");
            case ACCOUNT_NOT_FOUND -> messages.send(sender, "error.player-not-found",
                    Placeholder.unparsed("player", recipient.lastName()));
            case SUCCESS -> {
                String formatted = Money.format(amount, currencySymbol);
                messages.send(sender, "payment.sent",
                        Placeholder.unparsed("player", recipient.lastName()),
                        Placeholder.unparsed("amount", formatted));
                Player onlineRecipient = Bukkit.getPlayer(recipient.uuid());
                if (onlineRecipient != null) {
                    messages.send(onlineRecipient, "payment.received",
                            Placeholder.unparsed("player", sender.getName()),
                            Placeholder.unparsed("amount", formatted));
                }
            }
        }
    }

    private boolean transactions(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length > 1) {
            usage(sender, "/transactions [page]");
            return true;
        }
        int page = 1;
        if (args.length == 1) {
            try {
                page = Integer.parseInt(args[0]);
                if (page < 1) {
                    throw new NumberFormatException("Page must be positive");
                }
            } catch (NumberFormatException exception) {
                messages.send(sender, "error.invalid-page");
                return true;
            }
        }
        int selectedPage = page;
        int offset;
        try {
            offset = Math.multiplyExact(selectedPage - 1, transactionPageSize);
        } catch (ArithmeticException exception) {
            messages.send(sender, "error.invalid-page");
            return true;
        }
        complete(sender,
                database.submit(() -> citizens.transactions(player.getUniqueId(), transactionPageSize, offset)),
                entries -> showTransactions(sender, selectedPage, entries));
        return true;
    }

    private void showTransactions(CommandSender sender, int page, List<LedgerEntry> entries) {
        messages.send(sender, "transactions.header", Placeholder.unparsed("page", Integer.toString(page)));
        if (entries.isEmpty()) {
            messages.send(sender, "transactions.empty");
            return;
        }
        for (LedgerEntry entry : entries) {
            Component description = switch (entry.type()) {
                case STARTING_BALANCE -> messages.component(sender, "transactions.starting-balance");
                case PAYMENT -> {
                    String key = entry.amountCents() < 0
                            ? "transactions.payment-sent" : "transactions.payment-received";
                    yield messages.component(sender, key,
                            Placeholder.unparsed("player",
                                    Optional.ofNullable(entry.counterpartyName()).orElse("Unknown")));
                }
                case BUSINESS_TRANSFER -> messages.component(sender,
                        entry.amountCents() < 0
                                ? "transactions.business-deposit" : "transactions.business-withdrawal");
                case BUSINESS_PAYMENT -> messages.component(sender, "transactions.business-payment");
                case SHOP_PURCHASE -> messages.component(sender, "transactions.shop-purchase");
                case SHOP_SALE -> messages.component(sender, "transactions.shop-sale");
                case CLAIM_BLOCK_PURCHASE -> messages.component(sender, "transactions.claim-block-purchase");
                case PROPERTY_PURCHASE -> messages.component(sender, "transactions.property-purchase");
                case PROPERTY_RENT -> messages.component(sender, "transactions.property-rent");
                case PROPERTY_RENT_REFUND -> messages.component(sender, "transactions.property-rent-refund");
                case PROPERTY_RENT_INCOME -> messages.component(sender, "transactions.property-rent-income");
            };
            messages.send(sender, "transactions.entry",
                    Placeholder.unparsed("date", DATE.format(entry.createdAt())),
                    Placeholder.unparsed("amount", Money.format(entry.amountCents(), currencySymbol)),
                    Placeholder.component("description", description));
        }
    }

    private boolean about(CommandSender sender, String[] args) {
        if (args.length > 1) {
            usage(sender, "/about [player]");
            return true;
        }
        if (args.length == 0 && !(sender instanceof Player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length == 1 && !sender.hasPermission("opencivitas.about.others")) {
            messages.send(sender, "error.no-permission");
            return true;
        }
        CompletableFuture<Optional<CitizenProfile>> request = args.length == 0
                ? database.submit(() -> citizens.find(((Player) sender).getUniqueId()))
                : database.submit(() -> citizens.findByName(args[0]));
        complete(sender, request, profile -> {
            if (profile.isEmpty()) {
                messages.send(sender, "error.player-not-found",
                        Placeholder.unparsed("player", args.length == 0 ? sender.getName() : args[0]));
                return;
            }
            CitizenProfile citizen = profile.get();
            messages.send(sender, "about.header", Placeholder.unparsed("player", citizen.lastName()));
            messages.send(sender, "about.joined", Placeholder.unparsed("date", DATE.format(citizen.joinedAt())));
            messages.send(sender, "about.balance",
                    Placeholder.unparsed("amount", Money.format(citizen.balanceCents(), currencySymbol)));
        });
        return true;
    }

    private boolean locale(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        String available = String.join(", ", messages.supported());
        if (args.length == 0) {
            messages.send(sender, "locale.current", Placeholder.unparsed("locale", messages.locale(sender)));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            messages.send(sender, "locale.available", Placeholder.unparsed("locales", available));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reset")) {
            complete(sender, database.submit(() -> {
                citizens.setPreferredLocale(player.getUniqueId(), null);
                return null;
            }), ignored -> {
                messages.setPreference(player.getUniqueId(), null);
                messages.send(sender, "locale.reset");
            });
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            if (!LocaleResolver.isExactSupported(args[1], messages.supported())) {
                messages.send(sender, "locale.unsupported", Placeholder.unparsed("locales", available));
                return true;
            }
            String selected = LocaleResolver.resolve(args[1], messages.supported(), messages.defaultLocale());
            complete(sender, database.submit(() -> {
                citizens.setPreferredLocale(player.getUniqueId(), selected);
                return null;
            }), ignored -> {
                messages.setPreference(player.getUniqueId(), selected);
                messages.send(sender, "locale.changed", Placeholder.unparsed("locale", selected));
            });
            return true;
        }
        usage(sender, "/locale [list|set <locale>|reset]");
        return true;
    }

    private <T> void complete(CommandSender sender, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                scheduleError(sender, error);
                return;
            }
            success.accept(result);
        }));
    }

    private void scheduleError(CommandSender sender, Throwable error) {
        plugin.getLogger().log(Level.SEVERE, "An asynchronous command operation failed", error);
        messages.send(sender, "error.database");
    }

    private void usage(CommandSender sender, String usage) {
        messages.send(sender, "error.usage", Placeholder.unparsed("usage", usage));
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (command.getName().equalsIgnoreCase("locale")) {
            if (args.length == 1) {
                return filter(args[0], List.of("list", "set", "reset"));
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
                return filter(args[1], messages.supported());
            }
            return List.of();
        }
        if ((command.getName().equalsIgnoreCase("pay")
                || command.getName().equalsIgnoreCase("balance")
                || command.getName().equalsIgnoreCase("about")) && args.length == 1) {
            return filter(args[0], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        return List.of();
    }

    private static List<String> filter(String input, List<String> candidates) {
        String prefix = input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(candidate);
            }
        }
        return matches;
    }
}
