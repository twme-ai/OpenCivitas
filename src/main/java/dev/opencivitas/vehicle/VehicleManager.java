package dev.opencivitas.vehicle;

import dev.opencivitas.database.Database;
import dev.opencivitas.message.MessageService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public final class VehicleManager {
    private static final String ROLE_SEAT = "seat";
    private static final String ROLE_DISPLAY = "display";
    private static final String ROLE_INTERACTION = "interaction";

    private final JavaPlugin plugin;
    private final Database database;
    private final VehicleRepository vehicles;
    private final VehicleRegistry registry;
    private final VehicleItems items;
    private final MessageService messages;
    private final NamespacedKey vehicleIdKey;
    private final NamespacedKey roleKey;
    private final Map<UUID, VehicleState> known = new HashMap<>();
    private final Map<UUID, ActiveVehicle> active = new HashMap<>();
    private BukkitTask movementTask;
    private BukkitTask saveTask;

    public VehicleManager(
            JavaPlugin plugin,
            Database database,
            VehicleRepository vehicles,
            VehicleRegistry registry,
            VehicleItems items,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.vehicles = vehicles;
        this.registry = registry;
        this.items = items;
        this.messages = messages;
        vehicleIdKey = new NamespacedKey(plugin, "vehicle_id");
        roleKey = new NamespacedKey(plugin, "vehicle_entity_role");
    }

    public void start(Collection<VehicleState> states) {
        for (VehicleState state : states) {
            if (registry.find(state.typeId()).isPresent()) known.put(state.id(), state);
            else plugin.getLogger().warning("Ignoring vehicle with unknown type " + state.typeId());
        }
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) discover(chunk);
        }
        movementTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        saveTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::saveAsync, registry.saveIntervalTicks(), registry.saveIntervalTicks());
    }

    public void stop() {
        if (movementTask != null) movementTask.cancel();
        if (saveTask != null) saveTask.cancel();
        List<VehicleState> snapshots = new ArrayList<>();
        for (ActiveVehicle vehicle : List.copyOf(active.values())) {
            VehicleState snapshot = snapshot(vehicle);
            known.put(snapshot.id(), snapshot);
            snapshots.add(snapshot);
        }
        try {
            database.submit(() -> {
                for (VehicleState snapshot : snapshots) vehicles.updateTelemetry(snapshot);
                return null;
            }).join();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not save final vehicle telemetry", exception);
        }
        active.clear();
    }

    public void discover(Chunk chunk) {
        Map<UUID, ArmorStand> seats = new HashMap<>();
        Map<UUID, ItemDisplay> displays = new HashMap<>();
        Map<UUID, Interaction> interactions = new HashMap<>();
        for (Entity entity : chunk.getEntities()) {
            UUID id = id(entity).orElse(null);
            if (id == null) continue;
            String role = entity.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING);
            if (!known.containsKey(id)) {
                entity.remove();
            } else if (ROLE_SEAT.equals(role) && entity instanceof ArmorStand seat) {
                seats.put(id, seat);
            } else if (ROLE_DISPLAY.equals(role) && entity instanceof ItemDisplay display) {
                displays.put(id, display);
            } else if (ROLE_INTERACTION.equals(role) && entity instanceof Interaction interaction) {
                interactions.put(id, interaction);
            } else {
                entity.remove();
            }
        }
        for (Map.Entry<UUID, VehicleState> entry : known.entrySet()) {
            VehicleState state = entry.getValue();
            if (!state.worldName().equals(chunk.getWorld().getName())
                    || chunkCoordinate(state.x()) != chunk.getX()
                    || chunkCoordinate(state.z()) != chunk.getZ()
                    || active.containsKey(entry.getKey())) continue;
            ArmorStand seat = seats.remove(entry.getKey());
            if (seat == null) seat = spawnSeat(state, registry.find(state.typeId()).orElseThrow());
            ItemDisplay display = displays.remove(entry.getKey());
            if (display == null) display = spawnDisplay(state, registry.find(state.typeId()).orElseThrow());
            Interaction interaction = interactions.remove(entry.getKey());
            if (interaction == null) interaction = spawnInteraction(
                    state, registry.find(state.typeId()).orElseThrow());
            active.put(entry.getKey(), new ActiveVehicle(
                    state, registry.find(state.typeId()).orElseThrow(), seat, display, interaction));
        }
        seats.values().forEach(Entity::remove);
        displays.values().forEach(Entity::remove);
        interactions.values().forEach(Entity::remove);
    }

    public void suspend(Chunk chunk) {
        List<VehicleState> snapshots = new ArrayList<>();
        for (ActiveVehicle vehicle : List.copyOf(active.values())) {
            Location location = vehicle.seat().getLocation();
            if (!location.getWorld().equals(chunk.getWorld())
                    || location.getChunk().getX() != chunk.getX()
                    || location.getChunk().getZ() != chunk.getZ()) continue;
            VehicleState snapshot = snapshot(vehicle);
            known.put(snapshot.id(), snapshot);
            active.remove(snapshot.id());
            snapshots.add(snapshot);
        }
        if (snapshots.isEmpty()) return;
        database.submit(() -> {
            for (VehicleState snapshot : snapshots) vehicles.updateTelemetry(snapshot);
            return null;
        }).exceptionally(error -> {
            if (plugin.isEnabled()) plugin.getLogger().log(Level.WARNING, "Could not suspend vehicles", error);
            return null;
        });
    }

    public ActiveVehicle spawn(VehicleState state) {
        VehicleDefinition definition = registry.find(state.typeId()).orElseThrow();
        known.put(state.id(), state);
        ActiveVehicle existing = active.remove(state.id());
        if (existing != null) removeEntities(existing);
        ActiveVehicle vehicle = new ActiveVehicle(
                state, definition, spawnSeat(state, definition), spawnDisplay(state, definition),
                spawnInteraction(state, definition));
        active.put(state.id(), vehicle);
        return vehicle;
    }

    public Optional<ActiveVehicle> vehicle(Entity entity) {
        return id(entity).map(active::get);
    }

    public Optional<ActiveVehicle> target(Player player, double radius) {
        Entity ridden = player.getVehicle();
        if (ridden != null) {
            ActiveVehicle vehicle = vehicle(ridden).orElse(null);
            if (vehicle != null) return Optional.of(vehicle);
        }
        return player.getNearbyEntities(radius, radius, radius).stream()
                .map(this::vehicle).flatMap(Optional::stream)
                .distinct()
                .min(Comparator.comparingDouble(vehicle -> vehicle.seat().getLocation()
                        .distanceSquared(player.getLocation())));
    }

    public boolean mount(Player player, ActiveVehicle vehicle) {
        if (!vehicle.seat().isValid() || !vehicle.seat().getPassengers().isEmpty()) return false;
        vehicle.seat().addPassenger(player);
        vehicle.input(false, false, false, false, false, false);
        return player.getVehicle() != null && player.getVehicle().getUniqueId().equals(vehicle.seat().getUniqueId());
    }

    public void input(Player player, Input input) {
        Entity ridden = player.getVehicle();
        if (ridden == null) return;
        ActiveVehicle vehicle = vehicle(ridden).orElse(null);
        if (vehicle == null) return;
        vehicle.input(input.isForward(), input.isBackward(), input.isLeft(), input.isRight(),
                input.isJump(), input.isSprint());
    }

    public void apply(VehicleState state) {
        known.put(state.id(), state);
        ActiveVehicle vehicle = active.get(state.id());
        if (vehicle != null) vehicle.state(state);
    }

    public void remove(UUID id) {
        known.remove(id);
        ActiveVehicle vehicle = active.remove(id);
        if (vehicle != null) removeEntities(vehicle);
    }

    public void damage(ActiveVehicle vehicle, int amount) {
        int health = Math.max(0, vehicle.state().health() - Math.max(1, amount));
        VehicleState damaged = vehicle.state().withTelemetry(
                vehicle.state().worldName(), vehicle.state().x(), vehicle.state().y(), vehicle.state().z(),
                vehicle.state().yaw(), vehicle.state().fuel(), health, Instant.now());
        vehicle.state(damaged);
        known.put(damaged.id(), damaged);
        if (health > 0) return;
        Location location = vehicle.seat().getLocation();
        remove(vehicle.state().id());
        database.submit(() -> vehicles.destroy(damaged.id())).whenComplete((removed, error) -> {
            if (error != null && plugin.isEnabled()) {
                plugin.getLogger().log(Level.SEVERE, "Could not destroy vehicle " + damaged.id(), error);
            }
        });
        location.getWorld().dropItemNaturally(location, new org.bukkit.inventory.ItemStack(org.bukkit.Material.IRON_NUGGET, 4));
    }

    private void tick() {
        for (ActiveVehicle vehicle : List.copyOf(active.values())) {
            if (!vehicle.seat().isValid() || !vehicle.display().isValid() || !vehicle.interaction().isValid()) {
                active.remove(vehicle.state().id());
                continue;
            }
            Location before = vehicle.seat().getLocation();
            Player driver = driver(vehicle);
            if (driver == null) vehicle.clearInput();
            consumeFuel(vehicle, before, driver);
            updateMotion(vehicle, driver);
            updateDisplay(vehicle);
        }
    }

    private void consumeFuel(ActiveVehicle vehicle, Location current, Player driver) {
        VehicleState state = vehicle.state();
        if (driver == null || state.fuel() <= 0 || !state.worldName().equals(current.getWorld().getName())) return;
        double dx = current.getX() - state.x();
        double dy = current.getY() - state.y();
        double dz = current.getZ() - state.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double use = vehicle.fuelRemainder() + distance * vehicle.definition().fuelPerBlock();
        long whole = (long) Math.floor(use);
        vehicle.fuelRemainder(use - whole);
        long fuel = Math.max(0, state.fuel() - whole);
        vehicle.state(state.withTelemetry(current.getWorld().getName(), current.getX(), current.getY(),
                current.getZ(), current.getYaw(), fuel, state.health(), Instant.now()));
    }

    private void updateMotion(ActiveVehicle vehicle, Player driver) {
        VehicleDefinition definition = vehicle.definition();
        ArmorStand seat = vehicle.seat();
        if (driver == null || vehicle.state().fuel() <= 0) {
            vehicle.speed(vehicle.speed() * 0.82);
            if (driver != null && vehicle.state().fuel() <= 0 && !vehicle.fuelWarning()) {
                messages.send(driver, "vehicle.out-of-fuel");
                vehicle.fuelWarning(true);
            }
        } else {
            double speed = vehicle.speed();
            if (vehicle.forward()) speed += definition.acceleration();
            if (vehicle.backward()) speed -= definition.acceleration();
            if (!vehicle.forward() && !vehicle.backward()) speed *= 0.88;
            double reverseLimit = definition.maximumSpeed() * 0.45;
            vehicle.speed(Math.max(-reverseLimit, Math.min(definition.maximumSpeed(), speed)));
        }

        float yaw = seat.getLocation().getYaw();
        if (Math.abs(vehicle.speed()) > 0.005) {
            float turn = definition.category() == VehicleCategory.AIR ? 2.0f : 3.5f;
            if (vehicle.left()) yaw -= turn;
            if (vehicle.right()) yaw += turn;
        }
        seat.setRotation(yaw, 0);
        double radians = Math.toRadians(yaw);
        double x = -Math.sin(radians) * vehicle.speed();
        double z = Math.cos(radians) * vehicle.speed();
        Vector old = seat.getVelocity();
        double y = switch (definition.category()) {
            case GROUND -> climbVelocity(seat, x, z, old.getY());
            case WATER -> waterVelocity(seat, old.getY());
            case AIR -> vehicle.jump() ? 0.24 : vehicle.sprint() ? -0.18 : old.getY() * 0.55;
        };
        seat.setGravity(definition.category() == VehicleCategory.GROUND);
        seat.setVelocity(new Vector(x, y, z));
    }

    private static double climbVelocity(ArmorStand seat, double x, double z, double currentY) {
        Location ahead = seat.getLocation().add(x == 0 ? 0 : Math.copySign(0.65, x), 0, z == 0 ? 0 : Math.copySign(0.65, z));
        if (!ahead.getBlock().isPassable() && ahead.clone().add(0, 1, 0).getBlock().isPassable()) return 0.38;
        return currentY;
    }

    private static double waterVelocity(ArmorStand seat, double currentY) {
        Location location = seat.getLocation();
        boolean inWater = location.getBlock().getType() == org.bukkit.Material.WATER
                || location.clone().subtract(0, 0.5, 0).getBlock().getType() == org.bukkit.Material.WATER;
        return inWater ? currentY * 0.4 : -0.12;
    }

    private void updateDisplay(ActiveVehicle vehicle) {
        Location location = vehicle.seat().getLocation().add(0, 0.25, 0);
        location.setYaw(vehicle.seat().getLocation().getYaw());
        location.setPitch(0);
        vehicle.display().teleport(location);
        Location hitbox = vehicle.seat().getLocation().add(0, 0.15, 0);
        hitbox.setYaw(vehicle.seat().getLocation().getYaw());
        hitbox.setPitch(0);
        vehicle.interaction().teleport(hitbox);
    }

    private Player driver(ActiveVehicle vehicle) {
        return vehicle.seat().getPassengers().stream()
                .filter(Player.class::isInstance).map(Player.class::cast).findFirst().orElse(null);
    }

    private void saveAsync() {
        List<VehicleState> snapshots = new ArrayList<>();
        for (ActiveVehicle vehicle : active.values()) {
            VehicleState snapshot = snapshot(vehicle);
            vehicle.state(snapshot);
            known.put(snapshot.id(), snapshot);
            snapshots.add(snapshot);
        }
        if (snapshots.isEmpty()) return;
        database.submit(() -> {
            for (VehicleState state : snapshots) vehicles.updateTelemetry(state);
            return null;
        }).exceptionally(error -> {
            if (plugin.isEnabled()) plugin.getLogger().log(Level.WARNING, "Could not save vehicle telemetry", error);
            return null;
        });
    }

    private static VehicleState snapshot(ActiveVehicle vehicle) {
        Location location = vehicle.seat().getLocation();
        VehicleState state = vehicle.state();
        return state.withTelemetry(location.getWorld().getName(), location.getX(), location.getY(),
                location.getZ(), location.getYaw(), state.fuel(), state.health(), Instant.now());
    }

    private ArmorStand spawnSeat(VehicleState state, VehicleDefinition definition) {
        World world = Bukkit.getWorld(state.worldName());
        if (world == null) throw new IllegalStateException("Vehicle world is unavailable: " + state.worldName());
        Location location = new Location(world, state.x(), state.y(), state.z(), state.yaw(), 0);
        return world.spawn(location, ArmorStand.class, seat -> {
            seat.setInvisible(true);
            seat.setSmall(true);
            seat.setBasePlate(false);
            seat.setArms(false);
            seat.setMarker(false);
            seat.setCollidable(true);
            seat.setPersistent(true);
            seat.setGravity(definition.category() == VehicleCategory.GROUND);
            tag(seat, state.id(), ROLE_SEAT);
        });
    }

    private ItemDisplay spawnDisplay(VehicleState state, VehicleDefinition definition) {
        World world = Bukkit.getWorld(state.worldName());
        if (world == null) throw new IllegalStateException("Vehicle world is unavailable: " + state.worldName());
        Location location = new Location(world, state.x(), state.y() + 0.25, state.z(), state.yaw(), 0);
        return world.spawn(location, ItemDisplay.class, display -> {
            display.setItemStack(items.display(definition));
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setInterpolationDuration(1);
            display.setTeleportDuration(1);
            Vector3f scale = definition.category() == VehicleCategory.GROUND
                    ? new Vector3f(1.6f, 0.7f, 2.2f) : new Vector3f(1.8f, 0.65f, 2.5f);
            display.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0), new AxisAngle4f(), scale, new AxisAngle4f()));
            display.setPersistent(true);
            tag(display, state.id(), ROLE_DISPLAY);
        });
    }

    private Interaction spawnInteraction(VehicleState state, VehicleDefinition definition) {
        World world = Bukkit.getWorld(state.worldName());
        if (world == null) throw new IllegalStateException("Vehicle world is unavailable: " + state.worldName());
        Location location = new Location(world, state.x(), state.y() + 0.15, state.z(), state.yaw(), 0);
        return world.spawn(location, Interaction.class, interaction -> {
            interaction.setInteractionWidth(definition.category() == VehicleCategory.GROUND ? 1.7f : 2.1f);
            interaction.setInteractionHeight(1.25f);
            interaction.setResponsive(true);
            interaction.setPersistent(true);
            tag(interaction, state.id(), ROLE_INTERACTION);
        });
    }

    private void tag(Entity entity, UUID id, String role) {
        entity.getPersistentDataContainer().set(vehicleIdKey, PersistentDataType.STRING, id.toString());
        entity.getPersistentDataContainer().set(roleKey, PersistentDataType.STRING, role);
    }

    private Optional<UUID> id(Entity entity) {
        String value = entity.getPersistentDataContainer().get(vehicleIdKey, PersistentDataType.STRING);
        if (value == null) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static int chunkCoordinate(double coordinate) {
        return ((int) Math.floor(coordinate)) >> 4;
    }

    private static void removeEntities(ActiveVehicle vehicle) {
        vehicle.seat().eject();
        vehicle.seat().remove();
        vehicle.display().remove();
        vehicle.interaction().remove();
    }
}
