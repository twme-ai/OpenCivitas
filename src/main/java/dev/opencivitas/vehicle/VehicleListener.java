package dev.opencivitas.vehicle;

import dev.opencivitas.database.Database;
import dev.opencivitas.message.MessageService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class VehicleListener implements Listener {
    private final JavaPlugin plugin;
    private final Database database;
    private final VehicleRepository vehicles;
    private final VehicleRegistry registry;
    private final VehicleItems items;
    private final VehicleManager manager;
    private final VehicleAccessService access;
    private final VehicleStorageService storage;
    private final MessageService messages;

    public VehicleListener(
            JavaPlugin plugin,
            Database database,
            VehicleRepository vehicles,
            VehicleRegistry registry,
            VehicleItems items,
            VehicleManager manager,
            VehicleAccessService access,
            VehicleStorageService storage,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.vehicles = vehicles;
        this.registry = registry;
        this.items = items;
        this.manager = manager;
        this.access = access;
        this.storage = storage;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent event) {
        if (event.getPlayer().getVehicle() != null
                && manager.vehicle(event.getPlayer().getVehicle()).isPresent()) return;
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null
                || !event.getAction().isRightClick()) return;
        VehicleDefinition definition = items.vehicle(event.getItem()).orElse(null);
        if (definition == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        Location location = event.getClickedBlock().getRelative(event.getBlockFace()).getLocation().add(0.5, 0, 0.5);
        location.setYaw(player.getLocation().getYaw());
        if (!validPlacement(location, definition)) {
            messages.send(player, "vehicle.invalid-placement");
            return;
        }
        long storedFuel = items.storedFuel(event.getItem(), definition);
        int storedHealth = items.storedHealth(event.getItem(), definition);
        byte[] storedTrunk = items.storedTrunk(event.getItem());
        ItemStack reserved = takeOne(player);
        if (reserved == null) return;
        long now = System.currentTimeMillis();
        VehicleState state = new VehicleState(UUID.randomUUID(), definition.id(), player.getUniqueId(),
                player.getName(), location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), storedFuel, true, storedHealth,
                Instant.ofEpochMilli(now), Instant.ofEpochMilli(now));
        database.submit(() -> vehicles.create(
                state, registry.maximumOwned(), storedTrunk)).whenComplete((operation, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    restore(player, reserved, location);
                    plugin.getLogger().log(Level.SEVERE, "Could not place a vehicle", error);
                    messages.send(player, "error.database");
                } else if (operation.result() != VehicleResult.SUCCESS) {
                    restore(player, reserved, location);
                    error(player, operation.result());
                } else {
                    manager.spawn(operation.value());
                    messages.send(player, "vehicle.placed", Placeholder.unparsed(
                            "vehicle", registry.name(definition, messages.locale(player))));
                }
            });
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        ActiveVehicle vehicle = manager.vehicle(event.getRightClicked()).orElse(null);
        if (vehicle == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (player.getVehicle() != null) return;
        if (player.isSneaking()) {
            if (vehicle.definition().category() == VehicleCategory.AIR) storage.open(player, vehicle);
            else pickup(player, vehicle);
            return;
        }
        if (vehicle.state().locked() && !vehicle.state().ownerId().equals(player.getUniqueId())) {
            messages.send(player, "vehicle.locked");
            return;
        }
        if (!vehicle.seat().getPassengers().isEmpty()) {
            messages.send(player, "vehicle.occupied");
            return;
        }
        String license = vehicle.definition().licenseId();
        if (access.hasLicense(player.getUniqueId(), license)) {
            mount(player, vehicle);
            return;
        }
        access.refresh(player.getUniqueId(), loaded -> {
            ActiveVehicle current = manager.vehicle(vehicle.seat()).orElse(null);
            if (current == null) return;
            if (!loaded.hasLicense(license)) {
                messages.send(player, "vehicle.license-required", Placeholder.unparsed("license", license));
            } else {
                mount(player, current);
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!items.isVehicleRecipe(event.getRecipe()) || !(event.getWhoClicked() instanceof Player player)) return;
        if (access.isMechanic(player.getUniqueId())) return;
        event.setCancelled(true);
        access.refresh(player.getUniqueId());
        messages.send(player, "vehicle.mechanic-required");
    }

    @EventHandler(ignoreCancelled = true)
    public void onWorkbench(InventoryOpenEvent event) {
        if (event.getInventory().getType() == InventoryType.WORKBENCH && event.getPlayer() instanceof Player player) {
            access.refresh(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        ActiveVehicle vehicle = manager.vehicle(event.getEntity()).orElse(null);
        if (vehicle == null) return;
        event.setCancelled(true);
        Player player = attacker(event);
        if (player == null) return;
        long fuel = items.fuelAmount(player.getInventory().getItemInMainHand());
        if (player.isSneaking() && fuel > 0) {
            refuel(player, vehicle, fuel);
            return;
        }
        if (!vehicle.state().ownerId().equals(player.getUniqueId())
                && !access.isMechanic(player.getUniqueId())) {
            messages.send(player, "vehicle.not-owner");
            return;
        }
        manager.damage(vehicle, Math.max(1, (int) Math.ceil(event.getFinalDamage())));
        messages.send(player, "vehicle.damaged",
                Placeholder.unparsed("health", Integer.toString(vehicle.state().health())),
                Placeholder.unparsed("maximum", Integer.toString(vehicle.definition().maximumHealth())));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnvironmentalDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) return;
        if (manager.vehicle(event.getEntity()).isPresent()) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onManipulate(PlayerArmorStandManipulateEvent event) {
        if (manager.vehicle(event.getRightClicked()).isPresent()) event.setCancelled(true);
    }

    @EventHandler
    public void onInput(PlayerInputEvent event) {
        manager.input(event.getPlayer(), event.getInput());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDriverInteract(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick()) return;
        Player player = event.getPlayer();
        if (player.getVehicle() == null) return;
        ActiveVehicle vehicle = manager.vehicle(player.getVehicle()).orElse(null);
        if (vehicle == null) return;
        event.setCancelled(true);
        storage.open(player, vehicle);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        manager.discover(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        manager.suspend(event.getChunk());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        access.refresh(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        access.forget(event.getPlayer().getUniqueId());
    }

    private void mount(Player player, ActiveVehicle vehicle) {
        if (manager.mount(player, vehicle)) {
            messages.send(player, "vehicle.mounted");
        } else {
            messages.send(player, "vehicle.occupied");
        }
    }

    private void refuel(Player player, ActiveVehicle vehicle, long amount) {
        ItemStack reserved = takeOne(player);
        if (reserved == null) return;
        database.submit(() -> vehicles.refuel(
                vehicle.state().id(), player.getUniqueId(), amount,
                vehicle.definition().maximumFuel(), System.currentTimeMillis()))
                .whenComplete((operation, error) -> {
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null) {
                            restore(player, reserved, vehicle.seat().getLocation());
                            plugin.getLogger().log(Level.SEVERE, "Could not refuel a vehicle", error);
                            messages.send(player, "error.database");
                        } else if (operation.result() != VehicleResult.SUCCESS) {
                            restore(player, reserved, vehicle.seat().getLocation());
                            error(player, operation.result());
                        } else {
                            manager.apply(operation.value());
                            messages.send(player, "vehicle.refueled",
                                    Placeholder.unparsed("fuel", Long.toString(operation.value().fuel())),
                                    Placeholder.unparsed("maximum", Long.toString(
                                            vehicle.definition().maximumFuel())));
                        }
                    });
                });
    }

    private void pickup(Player player, ActiveVehicle vehicle) {
        if (!vehicle.seat().getPassengers().isEmpty()) {
            messages.send(player, "vehicle.must-be-empty");
            return;
        }
        Location fallback = vehicle.seat().getLocation();
        database.submit(() -> vehicles.remove(vehicle.state().id(), player.getUniqueId()))
                .whenComplete((operation, error) -> {
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null) {
                            plugin.getLogger().log(Level.SEVERE, "Could not pick up a vehicle", error);
                            messages.send(player, "error.database");
                        } else if (operation.result() != VehicleResult.SUCCESS) {
                            error(player, operation.result());
                        } else {
                            manager.remove(operation.value().state().id());
                            ItemStack item = items.createVehicle(vehicle.definition(), messages.locale(player),
                                    operation.value().state().fuel(), operation.value().state().health(),
                                    operation.value().storage());
                            restore(player, item, fallback);
                            messages.send(player, "vehicle.picked-up");
                        }
                    });
                });
    }

    private static Player attacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }

    private static boolean validPlacement(Location location, VehicleDefinition definition) {
        if (!location.getWorld().getWorldBorder().isInside(location)
                || !location.getBlock().isPassable()
                || !location.clone().add(0, 1, 0).getBlock().isPassable()) return false;
        if (definition.category() != VehicleCategory.WATER) return true;
        Material at = location.getBlock().getType();
        Material below = location.clone().subtract(0, 1, 0).getBlock().getType();
        return at == Material.WATER || below == Material.WATER;
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

    private static void restore(Player player, ItemStack item, Location fallback) {
        if (player.isOnline()) {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
            leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(
                    player.getLocation(), leftover));
        } else {
            fallback.getWorld().dropItemNaturally(fallback, item);
        }
    }

    private void error(Player player, VehicleResult result) {
        messages.send(player, switch (result) {
            case CITIZEN_NOT_FOUND -> "vehicle.citizen-not-found";
            case OWNER_LIMIT_REACHED -> "vehicle.owner-limit";
            case VEHICLE_NOT_FOUND -> "vehicle.not-found";
            case NOT_OWNER -> "vehicle.not-owner";
            case SELF_TRANSFER -> "vehicle.self-transfer";
            default -> "vehicle.operation-failed";
        });
    }
}
