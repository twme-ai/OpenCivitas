package dev.opencivitas.command;

import dev.opencivitas.business.Business;
import dev.opencivitas.business.BusinessLedgerEntry;
import dev.opencivitas.business.BusinessOperation;
import dev.opencivitas.business.BusinessRepository;
import dev.opencivitas.business.BusinessResult;
import dev.opencivitas.business.BusinessRole;
import dev.opencivitas.citizen.CitizenProfile;
import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.economy.Money;
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
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class BusinessCommand implements CommandExecutor, TabCompleter {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{1,30}[a-z0-9]");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final JavaPlugin plugin;
    private final Database database;
    private final CitizenRepository citizens;
    private final BusinessRepository businesses;
    private final MessageService messages;
    private final String currencySymbol;
    private final int pageSize;

    public BusinessCommand(
            JavaPlugin plugin,
            Database database,
            CitizenRepository citizens,
            BusinessRepository businesses,
            MessageService messages,
            String currencySymbol,
            int pageSize
    ) {
        this.plugin = plugin;
        this.database = database;
        this.citizens = citizens;
        this.businesses = businesses;
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
        if (args.length == 0) {
            messages.send(sender, "business.usage");
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(sender, args);
            case "info" -> info(sender, args);
            case "list" -> list(sender, args);
            case "deposit" -> transfer(sender, args, TransferCommand.DEPOSIT);
            case "withdraw" -> transfer(sender, args, TransferCommand.WITHDRAW);
            case "pay" -> pay(sender, args);
            case "transactions" -> transactions(sender, args);
            case "disband" -> disband(sender, args);
            default -> {
                messages.send(sender, "business.usage");
                yield true;
            }
        };
    }

    private boolean create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 2) {
            usage(sender, "/business create <name>");
            return true;
        }
        String slug = args[1].toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(slug).matches()) {
            messages.send(sender, "business.invalid-name");
            return true;
        }
        String displayName = displayName(slug);
        complete(sender, database.submit(() -> businesses.create(player.getUniqueId(), slug, displayName)), result -> {
            switch (result) {
                case SUCCESS -> messages.send(sender, "business.created",
                        Placeholder.unparsed("business", displayName));
                case MISSING_QUALIFICATION -> messages.send(sender, "business.missing-qualification");
                case NAME_TAKEN -> messages.send(sender, "business.name-taken");
                default -> operationError(sender, result, slug);
            }
        });
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        if (args.length != 2) {
            usage(sender, "/business info <business>");
            return true;
        }
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        boolean auditor = sender.hasPermission("opencivitas.business.audit");
        complete(sender, database.submit(() -> {
            Optional<Business> business = businesses.find(args[1]);
            Optional<BusinessRole> role = business.isPresent() && viewer != null
                    ? businesses.role(args[1], viewer) : Optional.empty();
            return new BusinessView(business, role);
        }), view -> {
            if (view.business().isEmpty()) {
                businessNotFound(sender, args[1]);
                return;
            }
            Business business = view.business().get();
            messages.send(sender, "business.info-header",
                    Placeholder.unparsed("business", business.displayName()),
                    Placeholder.unparsed("slug", business.slug()));
            messages.send(sender, "business.info-proprietor",
                    Placeholder.unparsed("player", business.proprietorName()));
            messages.send(sender, "business.info-status",
                    Placeholder.unparsed("status", business.status().name().toLowerCase(Locale.ROOT)));
            if (view.role().map(BusinessRole::canManageFunds).orElse(false) || auditor) {
                messages.send(sender, "business.info-balance",
                        Placeholder.unparsed("balance", Money.format(business.balanceCents(), currencySymbol)));
            }
        });
        return true;
    }

    private boolean list(CommandSender sender, String[] args) {
        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                messages.send(sender, "error.player-only");
                return true;
            }
            complete(sender, database.submit(() -> businesses.list(player.getUniqueId())),
                    result -> showBusinesses(sender, result));
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("all")) {
            if (args.length > 3) {
                usage(sender, "/business list all [page]");
                return true;
            }
            Optional<Integer> page = page(sender, args.length == 3 ? args[2] : null);
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
            complete(sender, database.submit(() -> businesses.listAll(pageSize, offset)),
                    result -> showBusinesses(sender, result));
            return true;
        }
        if (args.length == 2) {
            if (!sender.hasPermission("opencivitas.business.list.others")) {
                messages.send(sender, "error.no-permission");
                return true;
            }
            complete(sender, database.submit(() -> {
                Optional<CitizenProfile> profile = citizens.findByName(args[1]);
                return new BusinessListView(
                        profile,
                        profile.isEmpty() ? List.of() : businesses.list(profile.get().uuid())
                );
            }), view -> {
                if (view.profile().isEmpty()) {
                    messages.send(sender, "error.player-not-found", Placeholder.unparsed("player", args[1]));
                    return;
                }
                showBusinesses(sender, view.businesses());
            });
            return true;
        }
        usage(sender, "/business list [player|all [page]]");
        return true;
    }

    private void showBusinesses(CommandSender sender, List<Business> result) {
        messages.send(sender, "business.list-header");
        if (result.isEmpty()) {
            messages.send(sender, "business.list-empty");
            return;
        }
        for (Business business : result) {
            messages.send(sender, "business.list-entry",
                    Placeholder.unparsed("business", business.displayName()),
                    Placeholder.unparsed("slug", business.slug()));
        }
    }

    private boolean transfer(CommandSender sender, String[] args, TransferCommand command) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 3) {
            usage(sender, "/business " + command.commandName + " <business> <amount>");
            return true;
        }
        Optional<Long> amount = amount(sender, args[2]);
        if (amount.isEmpty()) {
            return true;
        }
        CompletableFuture<BusinessOperation> request = database.submit(() -> command == TransferCommand.DEPOSIT
                ? businesses.deposit(player.getUniqueId(), args[1], amount.get())
                : businesses.withdraw(player.getUniqueId(), args[1], amount.get()));
        complete(sender, request, operation -> {
            if (operation.result() != BusinessResult.SUCCESS) {
                operationError(sender, operation.result(), args[1]);
                return;
            }
            String key = command == TransferCommand.DEPOSIT ? "business.deposited" : "business.withdrew";
            messages.send(sender, key,
                    Placeholder.unparsed("business", displayName(args[1])),
                    Placeholder.unparsed("amount", Money.format(amount.get(), currencySymbol)),
                    Placeholder.unparsed("balance", Money.format(operation.businessBalanceCents(), currencySymbol)));
        });
        return true;
    }

    private boolean pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 4) {
            usage(sender, "/business pay <business> <player> <amount>");
            return true;
        }
        Optional<Long> amount = amount(sender, args[3]);
        if (amount.isEmpty()) {
            return true;
        }
        complete(sender, database.submit(() -> {
            Optional<CitizenProfile> recipient = citizens.findByName(args[2]);
            if (recipient.isEmpty()) {
                return new BusinessPayment(Optional.empty(),
                        new BusinessOperation(BusinessResult.CITIZEN_NOT_FOUND, 0));
            }
            BusinessOperation operation = businesses.pay(
                    player.getUniqueId(), args[1], recipient.get().uuid(), amount.get());
            return new BusinessPayment(recipient, operation);
        }), payment -> {
            if (payment.recipient().isEmpty()) {
                messages.send(sender, "error.player-not-found", Placeholder.unparsed("player", args[2]));
                return;
            }
            if (payment.operation().result() != BusinessResult.SUCCESS) {
                operationError(sender, payment.operation().result(), args[1]);
                return;
            }
            messages.send(sender, "business.paid",
                    Placeholder.unparsed("business", displayName(args[1])),
                    Placeholder.unparsed("player", payment.recipient().get().lastName()),
                    Placeholder.unparsed("amount", Money.format(amount.get(), currencySymbol)),
                    Placeholder.unparsed("balance",
                            Money.format(payment.operation().businessBalanceCents(), currencySymbol)));
        });
        return true;
    }

    private boolean transactions(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 3) {
            usage(sender, "/business transactions <business> [page]");
            return true;
        }
        Optional<Integer> page = page(sender, args.length == 3 ? args[2] : null);
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
        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        boolean auditor = sender.hasPermission("opencivitas.business.audit");
        complete(sender, database.submit(() -> {
            Optional<Business> business = businesses.find(args[1]);
            Optional<BusinessRole> role = business.isPresent() && viewer != null
                    ? businesses.role(args[1], viewer) : Optional.empty();
            boolean authorized = auditor
                    || role.map(BusinessRole::canManageFunds).orElse(false);
            List<BusinessLedgerEntry> entries = business.isPresent() && authorized
                    ? businesses.ledger(args[1], pageSize, offset) : List.of();
            return new LedgerView(business, authorized, entries);
        }), view -> {
            if (view.business().isEmpty()) {
                businessNotFound(sender, args[1]);
                return;
            }
            if (!view.authorized()) {
                messages.send(sender, "business.no-permission");
                return;
            }
            showLedger(sender, view.business().get(), page.get(), view.entries());
        });
        return true;
    }

    private void showLedger(
            CommandSender sender,
            Business business,
            int page,
            List<BusinessLedgerEntry> entries
    ) {
        messages.send(sender, "business.ledger-header",
                Placeholder.unparsed("business", business.displayName()),
                Placeholder.unparsed("page", Integer.toString(page)));
        if (entries.isEmpty()) {
            messages.send(sender, "business.ledger-empty");
            return;
        }
        for (BusinessLedgerEntry entry : entries) {
            String key = switch (entry.type()) {
                case "DEPOSIT" -> "business.ledger-deposit";
                case "WITHDRAWAL" -> "business.ledger-withdrawal";
                case "PAYMENT" -> "business.ledger-payment";
                case "DISBAND_REFUND" -> "business.ledger-disband";
                default -> "business.ledger-withdrawal";
            };
            Component description = messages.component(sender, key,
                    Placeholder.unparsed("actor", Optional.ofNullable(entry.actorName()).orElse("System")),
                    Placeholder.unparsed("player",
                            Optional.ofNullable(entry.counterpartyName()).orElse("Unknown")));
            messages.send(sender, "business.ledger-entry",
                    Placeholder.unparsed("date", DATE.format(entry.createdAt())),
                    Placeholder.unparsed("amount", Money.format(entry.amountCents(), currencySymbol)),
                    Placeholder.component("description", description));
        }
    }

    private boolean disband(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 2) {
            usage(sender, "/business disband <business>");
            return true;
        }
        complete(sender, database.submit(() -> businesses.disband(player.getUniqueId(), args[1])), operation -> {
            if (operation.result() == BusinessResult.SUCCESS) {
                messages.send(sender, "business.disbanded",
                        Placeholder.unparsed("business", displayName(args[1])));
            } else {
                operationError(sender, operation.result(), args[1]);
            }
        });
        return true;
    }

    private void operationError(CommandSender sender, BusinessResult result, String slug) {
        switch (result) {
            case BUSINESS_NOT_FOUND -> businessNotFound(sender, slug);
            case BUSINESS_INACTIVE -> messages.send(sender, "business.inactive");
            case NO_PERMISSION -> messages.send(sender, "business.no-permission");
            case INSUFFICIENT_PERSONAL_FUNDS -> messages.send(sender, "business.insufficient-personal");
            case INSUFFICIENT_BUSINESS_FUNDS -> messages.send(sender, "business.insufficient-business");
            case MISSING_QUALIFICATION -> messages.send(sender, "business.missing-qualification");
            case NAME_TAKEN -> messages.send(sender, "business.name-taken");
            default -> messages.send(sender, "error.database");
        }
    }

    private Optional<Long> amount(CommandSender sender, String input) {
        try {
            return Optional.of(Money.parsePositiveCents(input));
        } catch (IllegalArgumentException exception) {
            messages.send(sender, "error.invalid-amount");
            return Optional.empty();
        }
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

    private void businessNotFound(CommandSender sender, String slug) {
        messages.send(sender, "business.not-found", Placeholder.unparsed("business", slug));
    }

    private void usage(CommandSender sender, String usage) {
        messages.send(sender, "error.usage", Placeholder.unparsed("usage", usage));
    }

    private <T> void complete(CommandSender sender, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "An asynchronous business operation failed", error);
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
        if (args.length == 1) {
            return filter(args[0], List.of(
                    "create", "info", "list", "deposit", "withdraw", "pay", "transactions", "disband"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
            List<String> options = new ArrayList<>(onlineNames());
            options.add("all");
            return filter(args[1], options);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("pay")) {
            return filter(args[2], onlineNames());
        }
        return List.of();
    }

    private static List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private static List<String> filter(String input, Collection<String> candidates) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }

    private static String displayName(String slug) {
        String[] words = slug.split("-");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private enum TransferCommand {
        DEPOSIT("deposit"),
        WITHDRAW("withdraw");

        private final String commandName;

        TransferCommand(String commandName) {
            this.commandName = commandName;
        }
    }

    private record BusinessView(Optional<Business> business, Optional<BusinessRole> role) {
    }

    private record BusinessListView(Optional<CitizenProfile> profile, List<Business> businesses) {
    }

    private record BusinessPayment(Optional<CitizenProfile> recipient, BusinessOperation operation) {
    }

    private record LedgerView(
            Optional<Business> business,
            boolean authorized,
            List<BusinessLedgerEntry> entries
    ) {
    }
}
