package dev.opencivitas.command;

import dev.opencivitas.citizen.CitizenProfile;
import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.message.MessageService;
import dev.opencivitas.vehicle.ActiveVehicle;
import dev.opencivitas.vehicle.VehicleAccessService;
import dev.opencivitas.vehicle.VehicleDefinition;
import dev.opencivitas.vehicle.VehicleItems;
import dev.opencivitas.vehicle.VehicleManager;
import dev.opencivitas.vehicle.VehicleOperation;
import dev.opencivitas.vehicle.VehicleRecipeView;
import dev.opencivitas.vehicle.VehicleRegistry;
import dev.opencivitas.vehicle.VehicleRepository;
import dev.opencivitas.vehicle.VehicleResult;
import dev.opencivitas.vehicle.VehicleState;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class VehicleCommand implements CommandExecutor, TabCompleter, Listener {
    private static final List<String> ACTIONS = List.of(
            "list", "types", "info", "lock", "refuel", "repair", "pickup", "transfer", "give");

    private final JavaPlugin plugin;
    private final Database database;
    private final CitizenRepository citizens;
    private final VehicleRepository vehicles;
    private final VehicleRegistry registry;
    private final VehicleItems items;
    private final VehicleManager manager;
    private final VehicleAccessService access;
    private final MessageService messages;

    public VehicleCommand(
            JavaPlugin plugin,
            Database database,
            CitizenRepository citizens,
            VehicleRepository vehicles,
            VehicleRegistry registry,
            VehicleItems items,
            VehicleManager manager,
            VehicleAccessService access,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.citizens = citizens;
        this.vehicles = vehicles;
        this.registry = registry;
        this.items = items;
        this.manager = manager;
        this.access = access;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("recipes")) return recipes(sender, args);
        if (args.length > 0 && args[0].equalsIgnoreCase("give")) return give(sender, args);
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length == 0) return usage(sender, "/vehicle <list|types|info|lock|refuel|repair|pickup|transfer>");
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(player, args);
            case "types" -> types(player, args);
            case "info" -> info(player, args);
            case "lock" -> lock(player, args);
            case "refuel" -> refuel(player, args);
            case "repair" -> repair(player, args);
            case "pickup" -> pickup(player, args);
            case "transfer" -> transfer(player, args);
            case "give" -> give(player, args);
            default -> usage(sender, "/vehicle <list|types|info|lock|refuel|repair|pickup|transfer>");
        };
    }

    private boolean list(Player player, String[] args) {
        if (args.length != 1) return usage(player, "/vehicle list");
        complete(player, database.submit(() -> vehicles.owned(player.getUniqueId())), owned -> {
            messages.send(player, "vehicle.list-header");
            if (owned.isEmpty()) messages.send(player, "vehicle.list-empty");
            for (VehicleState state : owned) {
                VehicleDefinition definition = registry.find(state.typeId()).orElse(null);
                String type = definition == null ? state.typeId() : registry.name(definition, messages.locale(player));
                messages.send(player, "vehicle.list-entry",
                        Placeholder.unparsed("id", shortId(state)),
                        Placeholder.unparsed("vehicle", type),
                        Placeholder.unparsed("fuel", Long.toString(state.fuel())),
                        Placeholder.unparsed("health", Integer.toString(state.health())));
            }
        });
        return true;
    }

    private boolean types(Player player, String[] args) {
        if (args.length != 1) return usage(player, "/vehicle types");
        messages.send(player, "vehicle.types-header");
        for (VehicleDefinition definition : registry.all()) messages.send(player, "vehicle.type-entry",
                Placeholder.unparsed("id", definition.id()),
                Placeholder.unparsed("vehicle", registry.name(definition, messages.locale(player))),
                Placeholder.unparsed("category", definition.category().name().toLowerCase(Locale.ROOT)),
                Placeholder.unparsed("license", definition.licenseId()));
        return true;
    }

    private boolean info(Player player, String[] args) {
        if (args.length != 1) return usage(player, "/vehicle info");
        ActiveVehicle vehicle = target(player);
        if (vehicle == null) return true;
        VehicleState state = vehicle.state();
        messages.send(player, "vehicle.info",
                Placeholder.unparsed("id", shortId(state)),
                Placeholder.unparsed("vehicle", registry.name(vehicle.definition(), messages.locale(player))),
                Placeholder.unparsed("owner", state.ownerName()),
                Placeholder.unparsed("fuel", Long.toString(state.fuel())),
                Placeholder.unparsed("maximum-fuel", Long.toString(vehicle.definition().maximumFuel())),
                Placeholder.unparsed("health", Integer.toString(state.health())),
                Placeholder.unparsed("maximum-health", Integer.toString(vehicle.definition().maximumHealth())),
                Placeholder.component("lock", messages.component(player,
                        state.locked() ? "vehicle.locked-state" : "vehicle.unlocked-state")));
        return true;
    }

    private boolean lock(Player player, String[] args) {
        if (args.length != 1) return usage(player, "/vehicle lock");
        ActiveVehicle vehicle = target(player);
        if (vehicle == null) return true;
        boolean locked = !vehicle.state().locked();
        complete(player, database.submit(() -> vehicles.setLocked(
                vehicle.state().id(), player.getUniqueId(), locked, System.currentTimeMillis())), operation -> {
            if (!successful(player, operation)) return;
            manager.apply(operation.value());
            messages.send(player, locked ? "vehicle.locked-now" : "vehicle.unlocked-now");
        });
        return true;
    }

    private boolean refuel(Player player, String[] args) {
        if (args.length != 1) return usage(player, "/vehicle refuel");
        ActiveVehicle vehicle = target(player);
        if (vehicle == null) return true;
        long amount = items.fuelAmount(player.getInventory().getItemInMainHand());
        if (amount <= 0) {
            messages.send(player, "vehicle.hold-fuel");
            return true;
        }
        ItemStack reserved = takeOne(player);
        if (reserved == null) return true;
        submitReserved(player, reserved, database.submit(() -> vehicles.refuel(
                vehicle.state().id(), player.getUniqueId(), amount,
                vehicle.definition().maximumFuel(), System.currentTimeMillis())), operation -> {
            if (!successful(player, operation)) return false;
            manager.apply(operation.value());
            messages.send(player, "vehicle.refueled",
                    Placeholder.unparsed("fuel", Long.toString(operation.value().fuel())),
                    Placeholder.unparsed("maximum", Long.toString(vehicle.definition().maximumFuel())));
            return true;
        });
        return true;
    }

    private boolean repair(Player player, String[] args) {
        if (args.length != 1) return usage(player, "/vehicle repair");
        ActiveVehicle vehicle = target(player);
        if (vehicle == null) return true;
        int amount = items.repairAmount(player.getInventory().getItemInMainHand());
        if (amount <= 0) {
            messages.send(player, "vehicle.hold-repair-kit");
            return true;
        }
        ItemStack reserved = takeOne(player);
        if (reserved == null) return true;
        submitReserved(player, reserved, database.submit(() -> vehicles.repair(
                vehicle.state().id(), player.getUniqueId(), amount,
                vehicle.definition().maximumHealth(), System.currentTimeMillis())), operation -> {
            if (!successful(player, operation)) return false;
            manager.apply(operation.value());
            messages.send(player, "vehicle.repaired",
                    Placeholder.unparsed("health", Integer.toString(operation.value().health())),
                    Placeholder.unparsed("maximum", Integer.toString(vehicle.definition().maximumHealth())));
            return true;
        });
        return true;
    }

    private boolean pickup(Player player, String[] args) {
        if (args.length != 1) return usage(player, "/vehicle pickup");
        ActiveVehicle vehicle = target(player);
        if (vehicle == null) return true;
        if (!vehicle.seat().getPassengers().isEmpty()) {
            messages.send(player, "vehicle.must-be-empty");
            return true;
        }
        complete(player, database.submit(() -> vehicles.remove(
                vehicle.state().id(), player.getUniqueId())), operation -> {
            if (!successful(player, operation)) return;
            manager.remove(operation.value().state().id());
            give(player, items.createVehicle(vehicle.definition(), messages.locale(player),
                    operation.value().state().fuel(), operation.value().state().health(),
                    operation.value().storage()));
            messages.send(player, "vehicle.picked-up");
        });
        return true;
    }

    private boolean transfer(Player player, String[] args) {
        if (args.length != 2) return usage(player, "/vehicle transfer <player>");
        ActiveVehicle vehicle = target(player);
        if (vehicle == null) return true;
        complete(player, database.submit(() -> {
            CitizenProfile target = citizens.findByName(args[1]).orElse(null);
            if (target == null) return new TransferTarget(
                    VehicleOperation.result(VehicleResult.CITIZEN_NOT_FOUND), null);
            VehicleOperation<VehicleState> operation = vehicles.transfer(
                    vehicle.state().id(), player.getUniqueId(), target.uuid(),
                    registry.maximumOwned(), System.currentTimeMillis());
            return new TransferTarget(operation, target);
        }), result -> {
            if (!successful(player, result.operation())) return;
            manager.apply(result.operation().value());
            messages.send(player, "vehicle.transferred",
                    Placeholder.unparsed("player", result.target().lastName()));
        });
        return true;
    }

    private boolean give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("opencivitas.vehicles.manage")) {
            messages.send(sender, "error.no-permission");
            return true;
        }
        if (args.length != 3) return usage(sender, "/vehicle give <player> <type|fuel|repair-kit>");
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(sender, "error.player-not-found", Placeholder.unparsed("player", args[1]));
            return true;
        }
        ItemStack item;
        String description;
        if (args[2].equalsIgnoreCase("fuel")) {
            item = items.fuel();
            description = "fuel";
        } else if (args[2].equalsIgnoreCase("repair-kit")) {
            item = items.repairKit();
            description = "repair kit";
        } else {
            VehicleDefinition definition = registry.find(args[2]).orElse(null);
            if (definition == null) {
                messages.send(sender, "vehicle.unknown-type", Placeholder.unparsed("vehicle", args[2]));
                return true;
            }
            item = items.createVehicle(definition, messages.locale(target));
            description = registry.name(definition, messages.locale(sender));
        }
        give(target, item);
        messages.send(sender, "vehicle.given",
                Placeholder.unparsed("item", description), Placeholder.unparsed("player", target.getName()));
        return true;
    }

    private boolean recipes(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 0) return usage(sender, "/recipes");
        CatalogHolder holder = new CatalogHolder(messages.component(player, "vehicle.recipes-title"));
        int slot = 0;
        for (VehicleRecipeView recipe : items.recipes()) holder.inventory.setItem(slot++, items.recipeIcon(recipe));
        player.openInventory(holder.inventory);
        return true;
    }

    @EventHandler
    public void onRecipeClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof CatalogHolder) && !(top.getHolder() instanceof DetailHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !(top.getHolder() instanceof CatalogHolder)
                || event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;
        String id = items.recipeId(event.getCurrentItem()).orElse(null);
        if (id == null) return;
        VehicleRecipeView recipe = items.recipe(id).orElse(null);
        if (recipe == null) return;
        DetailHolder holder = new DetailHolder(messages.component(player, "vehicle.recipe-title"));
        int[] slots = {10, 11, 12, 19, 20, 21, 28, 29, 30};
        for (int index = 0; index < slots.length; index++) {
            ItemStack ingredient = recipe.grid().get(index);
            if (ingredient != null) holder.inventory.setItem(slots[index], ingredient);
        }
        holder.inventory.setItem(25, recipe.result());
        player.openInventory(holder.inventory);
    }

    private ActiveVehicle target(Player player) {
        ActiveVehicle vehicle = manager.target(player, 6).orElse(null);
        if (vehicle == null) messages.send(player, "vehicle.not-found-nearby");
        return vehicle;
    }

    private boolean successful(Player player, VehicleOperation<?> operation) {
        if (operation.result() == VehicleResult.SUCCESS) return true;
        error(player, operation.result());
        return false;
    }

    private void error(Player player, VehicleResult result) {
        messages.send(player, switch (result) {
            case CITIZEN_NOT_FOUND -> "vehicle.citizen-not-found";
            case VEHICLE_NOT_FOUND -> "vehicle.not-found";
            case NOT_OWNER -> "vehicle.not-owner";
            case SELF_TRANSFER -> "vehicle.self-transfer";
            case OWNER_LIMIT_REACHED -> "vehicle.owner-limit";
            case FUEL_FULL -> "vehicle.fuel-full";
            case HEALTH_FULL -> "vehicle.health-full";
            default -> "vehicle.operation-failed";
        });
    }

    private <T> void complete(CommandSender sender, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((result, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Vehicle command failed", error);
                    messages.send(sender, "error.database");
                } else {
                    success.accept(result);
                }
            });
        });
    }

    private void submitReserved(
            Player player,
            ItemStack reserved,
            CompletableFuture<VehicleOperation<VehicleState>> future,
            java.util.function.Predicate<VehicleOperation<VehicleState>> success
    ) {
        future.whenComplete((operation, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    restore(player, reserved);
                    plugin.getLogger().log(Level.SEVERE, "Vehicle item operation failed", error);
                    messages.send(player, "error.database");
                } else if (!success.test(operation)) {
                    restore(player, reserved);
                }
            });
        });
    }

    private static ItemStack takeOne(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || held.getAmount() < 1) return null;
        ItemStack reserved = held.clone();
        reserved.setAmount(1);
        if (held.getAmount() == 1) player.getInventory().setItemInMainHand(null);
        else held.setAmount(held.getAmount() - 1);
        return reserved;
    }

    private static void restore(Player player, ItemStack item) {
        if (!player.isOnline()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
            return;
        }
        give(player, item);
    }

    private static void give(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private static String shortId(VehicleState state) {
        return state.id().toString().substring(0, 8);
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
        if (!command.getName().equalsIgnoreCase("vehicle")) return List.of();
        if (args.length == 1) return filter(ACTIONS, args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("transfer")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            List<String> types = new ArrayList<>(registry.all().stream().map(VehicleDefinition::id).toList());
            types.addAll(Arrays.asList("fuel", "repair-kit"));
            return filter(types, args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> values, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private record TransferTarget(VehicleOperation<VehicleState> operation, CitizenProfile target) { }

    private static final class CatalogHolder implements InventoryHolder {
        private final Inventory inventory;

        private CatalogHolder(net.kyori.adventure.text.Component title) {
            inventory = Bukkit.createInventory(this, 27, title);
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private static final class DetailHolder implements InventoryHolder {
        private final Inventory inventory;

        private DetailHolder(net.kyori.adventure.text.Component title) {
            inventory = Bukkit.createInventory(this, 45, title);
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
