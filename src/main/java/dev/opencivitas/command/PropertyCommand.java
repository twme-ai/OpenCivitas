package dev.opencivitas.command;

import dev.opencivitas.citizen.CitizenProfile;
import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.economy.Money;
import dev.opencivitas.message.MessageService;
import dev.opencivitas.property.Property;
import dev.opencivitas.property.PropertyDraft;
import dev.opencivitas.property.PropertyOperation;
import dev.opencivitas.property.PropertyRegistry;
import dev.opencivitas.property.PropertyRepository;
import dev.opencivitas.property.PropertyResult;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class PropertyCommand implements CommandExecutor, TabCompleter {
    private static final Pattern PLOT_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final int SEARCH_LIMIT = 50;

    private final JavaPlugin plugin;
    private final Database database;
    private final CitizenRepository citizens;
    private final PropertyRepository properties;
    private final PropertyRegistry registry;
    private final MessageService messages;
    private final String currencySymbol;
    private final int defaultRentalDays;
    private final Map<UUID, Selection> selections = new HashMap<>();

    public PropertyCommand(
            JavaPlugin plugin,
            Database database,
            CitizenRepository citizens,
            PropertyRepository properties,
            PropertyRegistry registry,
            MessageService messages,
            String currencySymbol,
            int defaultRentalDays
    ) {
        this.plugin = plugin;
        this.database = database;
        this.citizens = citizens;
        this.properties = properties;
        this.registry = registry;
        this.messages = messages;
        this.currencySymbol = currencySymbol;
        this.defaultRentalDays = defaultRentalDays;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length == 0) {
            messages.send(sender, "property.usage");
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> help(sender, args);
            case "me" -> me(sender, args);
            case "buy" -> buy(sender, args);
            case "rent" -> rent(sender, args);
            case "unrent" -> unrent(sender, args);
            case "search" -> search(sender, args);
            case "info" -> info(sender, args);
            case "add" -> trust(sender, args, true);
            case "remove" -> trust(sender, args, false);
            case "set" -> setHolder(sender, args);
            case "admin" -> admin(sender, args);
            default -> {
                messages.send(sender, "property.usage");
                yield true;
            }
        };
    }

    private boolean help(CommandSender sender, String[] args) {
        if (args.length != 1) {
            usage(sender, "/rl help");
            return true;
        }
        messages.send(sender, "property.usage");
        messages.send(sender, "property.usage-set");
        return true;
    }

    private boolean me(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 1) {
            usage(sender, "/rl me");
            return true;
        }
        List<Property> related = registry.relatedTo(player.getUniqueId());
        messages.send(sender, "property.me-header");
        if (related.isEmpty()) {
            messages.send(sender, "property.me-empty");
            return true;
        }
        for (Property property : related) {
            String relationship = player.getUniqueId().equals(property.titleholderId())
                    ? "property.relationship.titleholder"
                    : player.getUniqueId().equals(property.tenantId())
                    ? "property.relationship.tenant" : "property.relationship.trusted";
            messages.send(sender, "property.me-entry",
                    Placeholder.unparsed("plot", property.plotId()),
                    Placeholder.component("relationship", messages.component(sender, relationship)));
        }
        return true;
    }

    private boolean buy(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 2) {
            usage(sender, "/rl buy <plot>");
            return true;
        }
        complete(sender, database.submit(() -> properties.buy(
                player.getUniqueId(), args[1], Instant.now().toEpochMilli())), operation -> {
            if (!succeeded(sender, operation)) {
                return;
            }
            Property property = operation.property().orElseThrow();
            registry.upsert(property);
            messages.send(sender, "property.bought",
                    Placeholder.unparsed("plot", property.plotId()),
                    Placeholder.unparsed("price", Money.format(operation.amountCents(), currencySymbol)),
                    Placeholder.unparsed("balance", Money.format(operation.balanceCents(), currencySymbol)));
        });
        return true;
    }

    private boolean rent(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 2) {
            usage(sender, "/rl rent <plot>");
            return true;
        }
        complete(sender, database.submit(() -> properties.rent(
                player.getUniqueId(), args[1], Instant.now().toEpochMilli())), operation -> {
            if (!succeeded(sender, operation)) {
                return;
            }
            Property property = operation.property().orElseThrow();
            registry.upsert(property);
            messages.send(sender, "property.rented",
                    Placeholder.unparsed("plot", property.plotId()),
                    Placeholder.unparsed("price", Money.format(operation.amountCents(), currencySymbol)),
                    Placeholder.unparsed("ends", property.rentalEndsAt().toString()),
                    Placeholder.unparsed("balance", Money.format(operation.balanceCents(), currencySymbol)));
            notifyTitleholder(property, "property.rent-notice", player.getName());
        });
        return true;
    }

    private boolean unrent(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 2) {
            usage(sender, "/rl unrent <plot>");
            return true;
        }
        boolean administrator = sender.hasPermission("opencivitas.property.manage");
        complete(sender, database.submit(() -> properties.unrent(
                player.getUniqueId(), args[1], administrator, Instant.now().toEpochMilli())), operation -> {
            if (!succeeded(sender, operation)) {
                return;
            }
            Property property = operation.property().orElseThrow();
            registry.upsert(property);
            messages.send(sender, "property.unrented",
                    Placeholder.unparsed("plot", property.plotId()),
                    Placeholder.unparsed("refund", Money.format(operation.amountCents(), currencySymbol)),
                    Placeholder.unparsed("balance", Money.format(operation.balanceCents(), currencySymbol)));
        });
        return true;
    }

    private boolean search(CommandSender sender, String[] args) {
        if (args.length > 3) {
            usage(sender, "/rl search [minimum] [maximum]");
            return true;
        }
        Optional<Long> minimum = args.length >= 2 ? amount(sender, args[1], true) : Optional.of(0L);
        Optional<Long> maximum = args.length == 3 ? amount(sender, args[2], false) : Optional.of(Long.MAX_VALUE);
        if (minimum.isEmpty() || maximum.isEmpty()) {
            return true;
        }
        if (minimum.get() > maximum.get()) {
            messages.send(sender, "property.invalid-range");
            return true;
        }
        complete(sender, database.submit(() -> properties.searchRentals(
                minimum.get(), maximum.get(), SEARCH_LIMIT)), found -> {
            messages.send(sender, "property.search-header");
            if (found.isEmpty()) {
                messages.send(sender, "property.search-empty");
                return;
            }
            for (Property property : found) {
                messages.send(sender, "property.search-entry",
                        Placeholder.unparsed("plot", property.plotId()),
                        Placeholder.unparsed("price", Money.format(property.rentPriceCents(), currencySymbol)),
                        Placeholder.unparsed("days", Long.toString(
                                Duration.ofMillis(property.rentDurationMillis()).toDays())));
            }
        });
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        if (args.length != 2) {
            usage(sender, "/rl info <plot>");
            return true;
        }
        Optional<Property> selected = registry.find(args[1]);
        if (selected.isEmpty()) {
            propertyNotFound(sender, args[1]);
            return true;
        }
        Property property = selected.get();
        Component status = messages.component(sender, property.tenantId() != null
                ? "property.status.rented" : property.titleholderId() != null
                ? "property.status.owned" : "property.status.available");
        messages.send(sender, "property.info-header", Placeholder.unparsed("plot", property.plotId()));
        messages.send(sender, "property.info-status", Placeholder.component("status", status));
        messages.send(sender, "property.info-titleholder",
                Placeholder.unparsed("player", optionalName(property.titleholderName())));
        messages.send(sender, "property.info-tenant",
                Placeholder.unparsed("player", optionalName(property.tenantName())));
        messages.send(sender, "property.info-prices",
                Placeholder.unparsed("sale", optionalMoney(property.salePriceCents())),
                Placeholder.unparsed("rent", optionalMoney(property.rentPriceCents())));
        messages.send(sender, "property.info-bounds",
                Placeholder.unparsed("world", property.worldName()),
                Placeholder.unparsed("min_x", Integer.toString(property.minX())),
                Placeholder.unparsed("min_y", Integer.toString(property.minY())),
                Placeholder.unparsed("min_z", Integer.toString(property.minZ())),
                Placeholder.unparsed("max_x", Integer.toString(property.maxX())),
                Placeholder.unparsed("max_y", Integer.toString(property.maxY())),
                Placeholder.unparsed("max_z", Integer.toString(property.maxZ())));
        return true;
    }

    private boolean trust(CommandSender sender, String[] args, boolean add) {
        Player player = player(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 3) {
            usage(sender, add ? "/rl add <player> <plot>" : "/rl remove <player> <plot>");
            return true;
        }
        boolean administrator = sender.hasPermission("opencivitas.property.manage");
        complete(sender, targetMutation(args[1], target -> properties.trust(
                player.getUniqueId(), administrator, args[2], target.uuid(), add,
                Instant.now().toEpochMilli())), result -> finishTargetMutation(
                sender, result, add ? "property.trusted" : "property.untrusted", args[1]));
        return true;
    }

    private boolean setHolder(CommandSender sender, String[] args) {
        Player player = sender instanceof Player selected ? selected : null;
        boolean administrator = sender.hasPermission("opencivitas.property.manage");
        if (args.length != 4 || (!args[1].equalsIgnoreCase("titleholder")
                && !args[1].equalsIgnoreCase("tenant"))) {
            usage(sender, "/rl set <titleholder|tenant> <player> <plot>");
            return true;
        }
        if (player == null && !administrator) {
            messages.send(sender, "error.player-only");
            return true;
        }
        UUID actor = player == null ? null : player.getUniqueId();
        boolean titleholder = args[1].equalsIgnoreCase("titleholder");
        complete(sender, targetMutation(args[2], target -> titleholder
                ? properties.setTitleholder(
                        actor, administrator, args[3], target.uuid(), Instant.now().toEpochMilli())
                : properties.setTenant(
                        actor, administrator, args[3], target.uuid(), Instant.now().toEpochMilli())),
                result -> finishTargetMutation(
                        sender, result,
                        titleholder ? "property.titleholder-set" : "property.tenant-set", args[2]));
        return true;
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("opencivitas.property.manage")) {
            messages.send(sender, "error.no-permission");
            return true;
        }
        if (args.length < 2) {
            messages.send(sender, "property.admin-usage");
            return true;
        }
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "pos1" -> selectPosition(sender, true, args);
            case "pos2" -> selectPosition(sender, false, args);
            case "create" -> createProperty(sender, args);
            case "delete" -> deleteProperty(sender, args);
            default -> {
                messages.send(sender, "property.admin-usage");
                yield true;
            }
        };
    }

    private boolean selectPosition(CommandSender sender, boolean first, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return true;
        }
        if (args.length != 2) {
            usage(sender, first ? "/rl admin pos1" : "/rl admin pos2");
            return true;
        }
        Location location = player.getLocation();
        Selection current = selections.getOrDefault(player.getUniqueId(), new Selection(null, null));
        Position position = new Position(
                location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        selections.put(player.getUniqueId(), first
                ? new Selection(position, current.second()) : new Selection(current.first(), position));
        messages.send(sender, first ? "property.position-one" : "property.position-two",
                Placeholder.unparsed("world", position.world()),
                Placeholder.unparsed("x", Integer.toString(position.x())),
                Placeholder.unparsed("y", Integer.toString(position.y())),
                Placeholder.unparsed("z", Integer.toString(position.z())));
        return true;
    }

    private boolean createProperty(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null) {
            return true;
        }
        if (args.length < 5 || args.length > 6) {
            usage(sender, "/rl admin create <plot> <sale|-> <rent|-> [days]");
            return true;
        }
        String plotId = args[2].toLowerCase(Locale.ROOT);
        if (!PLOT_ID.matcher(plotId).matches()) {
            messages.send(sender, "property.invalid-id");
            return true;
        }
        Optional<Long> sale = optionalPrice(sender, args[3]);
        Optional<Long> rent = optionalPrice(sender, args[4]);
        if (sale.isEmpty() && !args[3].equals("-") || rent.isEmpty() && !args[4].equals("-")) {
            return true;
        }
        Long salePrice = args[3].equals("-") ? null : sale.orElseThrow();
        Long rentPrice = args[4].equals("-") ? null : rent.orElseThrow();
        if (salePrice == null && rentPrice == null) {
            messages.send(sender, "property.price-required");
            return true;
        }
        int days = defaultRentalDays;
        if (args.length == 6) {
            try {
                days = Integer.parseInt(args[5]);
                if (days < 1 || days > 3_650) {
                    throw new NumberFormatException("Rental days outside range");
                }
            } catch (NumberFormatException exception) {
                messages.send(sender, "property.invalid-days");
                return true;
            }
        }
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null || selection.first() == null || selection.second() == null
                || !selection.first().world().equals(selection.second().world())) {
            messages.send(sender, "property.selection-required");
            return true;
        }
        Position first = selection.first();
        Position second = selection.second();
        PropertyDraft draft = new PropertyDraft(
                plotId, first.world(), first.x(), first.y(), first.z(),
                second.x(), second.y(), second.z(), salePrice, rentPrice,
                Duration.ofDays(days).toMillis());
        complete(sender, database.submit(() -> properties.create(draft, Instant.now().toEpochMilli())), operation -> {
            if (!succeeded(sender, operation)) {
                return;
            }
            Property property = operation.property().orElseThrow();
            registry.upsert(property);
            selections.remove(player.getUniqueId());
            messages.send(sender, "property.created", Placeholder.unparsed("plot", property.plotId()));
        });
        return true;
    }

    private boolean deleteProperty(CommandSender sender, String[] args) {
        if (args.length != 3) {
            usage(sender, "/rl admin delete <plot>");
            return true;
        }
        Optional<Property> selected = registry.find(args[2]);
        if (selected.isEmpty()) {
            propertyNotFound(sender, args[2]);
            return true;
        }
        long propertyId = selected.get().id();
        complete(sender, database.submit(() -> properties.delete(args[2])), operation -> {
            if (!succeeded(sender, operation)) {
                return;
            }
            registry.remove(propertyId);
            messages.send(sender, "property.deleted", Placeholder.unparsed("plot", args[2]));
        });
        return true;
    }

    private CompletableFuture<TargetMutation> targetMutation(
            String name, TargetOperation operation) {
        return database.submit(() -> {
            Optional<CitizenProfile> target = citizens.findByName(name);
            PropertyOperation result = target.isEmpty()
                    ? PropertyOperation.failed(PropertyResult.CITIZEN_NOT_FOUND)
                    : operation.apply(target.get());
            return new TargetMutation(target, result);
        });
    }

    private void finishTargetMutation(
            CommandSender sender, TargetMutation result, String message, String requestedName) {
        if (result.target().isEmpty()) {
            messages.send(sender, "error.player-not-found", Placeholder.unparsed("player", requestedName));
            return;
        }
        if (!succeeded(sender, result.operation())) {
            return;
        }
        Property property = result.operation().property().orElseThrow();
        registry.upsert(property);
        messages.send(sender, message,
                Placeholder.unparsed("player", result.target().get().lastName()),
                Placeholder.unparsed("plot", property.plotId()));
    }

    private boolean succeeded(CommandSender sender, PropertyOperation operation) {
        if (operation.result() == PropertyResult.SUCCESS) {
            return true;
        }
        String key = switch (operation.result()) {
            case PROPERTY_NOT_FOUND -> "property.not-found";
            case PLOT_ID_TAKEN -> "property.id-taken";
            case OVERLAP -> "property.overlap";
            case NOT_FOR_SALE -> "property.not-for-sale";
            case NOT_FOR_RENT -> "property.not-for-rent";
            case NOT_RENTED -> "property.not-rented";
            case OCCUPIED -> "property.occupied";
            case NO_PERMISSION -> "property.no-permission";
            case INSUFFICIENT_FUNDS -> "property.insufficient-funds";
            case CITIZEN_NOT_FOUND -> "property.citizen-not-found";
            case ALREADY_TRUSTED -> "property.already-trusted";
            case NOT_TRUSTED -> "property.not-trusted";
            case SELF -> "property.self";
            default -> "property.failed";
        };
        messages.send(sender, key);
        return false;
    }

    private Optional<Long> optionalPrice(CommandSender sender, String input) {
        if (input.equals("-")) {
            return Optional.empty();
        }
        return amount(sender, input, false);
    }

    private Optional<Long> amount(CommandSender sender, String input, boolean allowZero) {
        try {
            long value = allowZero ? Money.parseCents(input) : Money.parsePositiveCents(input);
            return Optional.of(value);
        } catch (IllegalArgumentException exception) {
            messages.send(sender, "error.invalid-amount");
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

    private void notifyTitleholder(Property property, String key, String renter) {
        if (property.titleholderId() == null) {
            return;
        }
        Player online = Bukkit.getPlayer(property.titleholderId());
        if (online != null) {
            messages.send(online, key,
                    Placeholder.unparsed("player", renter),
                    Placeholder.unparsed("plot", property.plotId()));
        }
    }

    private String optionalMoney(Long cents) {
        return cents == null ? "-" : Money.format(cents, currencySymbol);
    }

    private static String optionalName(String value) {
        return value == null ? "-" : value;
    }

    private void propertyNotFound(CommandSender sender, String plot) {
        messages.send(sender, "property.not-found", Placeholder.unparsed("plot", plot));
    }

    private void usage(CommandSender sender, String usage) {
        messages.send(sender, "error.usage", Placeholder.unparsed("usage", usage));
    }

    private <T> void complete(CommandSender sender, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "An asynchronous property operation failed", error);
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
            List<String> options = new java.util.ArrayList<>(List.of(
                    "help", "me", "buy", "rent", "unrent", "search", "info", "add", "remove", "set"));
            if (sender.hasPermission("opencivitas.property.manage")) {
                options.add("admin");
            }
            return filter(args[0], options);
        }
        if (args.length == 2 && List.of("buy", "rent", "unrent", "info").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(args[1], registry.all().stream().map(Property::plotId).toList());
        }
        if (args.length == 2 && List.of("add", "remove").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(args[1], onlineNames());
        }
        if (args.length == 3 && List.of("add", "remove").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(args[2], registry.all().stream().map(Property::plotId).toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            return filter(args[1], List.of("titleholder", "tenant"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            return filter(args[2], onlineNames());
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("set")) {
            return filter(args[3], registry.all().stream().map(Property::plotId).toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return filter(args[1], List.of("pos1", "pos2", "create", "delete"));
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

    @FunctionalInterface
    private interface TargetOperation {
        PropertyOperation apply(CitizenProfile target) throws Exception;
    }

    private record TargetMutation(Optional<CitizenProfile> target, PropertyOperation operation) {
    }

    private record Position(String world, int x, int y, int z) {
    }

    private record Selection(Position first, Position second) {
    }
}
