package dev.opencivitas.security;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public final class CameraManager {
    private static final String VISUAL = "visual";
    private static final String HITBOX = "hitbox";
    private static final String ANCHOR = "anchor";

    private final JavaPlugin plugin;
    private final SecurityRegistry registry;
    private final SecurityItems items;
    private final NamespacedKey cameraIdKey;
    private final NamespacedKey partKey;
    private final Map<Long, CameraEntities> entities = new HashMap<>();

    public CameraManager(JavaPlugin plugin, SecurityRegistry registry, SecurityItems items) {
        this.plugin = plugin;
        this.registry = registry;
        this.items = items;
        cameraIdKey = new NamespacedKey(plugin, "security_camera_id");
        partKey = new NamespacedKey(plugin, "security_camera_part");
    }

    public void start() {
        for (SecurityCamera camera : registry.cameras()) {
            World world = Bukkit.getWorld(camera.world());
            if (world != null && world.isChunkLoaded(chunk(camera.x()), chunk(camera.z()))) recover(camera);
        }
    }

    public void place(SecurityCamera camera) {
        registry.upsert(camera);
        removeEntities(camera.id());
        entities.put(camera.id(), spawn(camera));
    }

    public void update(SecurityCamera camera) {
        registry.upsert(camera);
        CameraEntities current = entities.get(camera.id());
        if (current == null || !current.valid()) {
            recover(camera);
            return;
        }
        Location visual = location(camera);
        current.visual().teleport(visual);
        current.hitbox().teleport(visual);
        current.anchor().teleport(anchorLocation(camera, current.anchor()));
    }

    public void remove(long cameraId) {
        registry.removeCamera(cameraId);
        removeEntities(cameraId);
    }

    public Optional<Long> cameraId(Entity entity) {
        Long id = entity.getPersistentDataContainer().get(cameraIdKey, PersistentDataType.LONG);
        return Optional.ofNullable(id);
    }

    public void onChunkLoad(Chunk chunk) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Map<Long, List<Entity>> found = new HashMap<>();
            for (Entity entity : chunk.getEntities()) {
                Long id = entity.getPersistentDataContainer().get(cameraIdKey, PersistentDataType.LONG);
                if (id == null) continue;
                if (registry.camera(id).isEmpty()) {
                    entity.remove();
                    continue;
                }
                found.computeIfAbsent(id, ignored -> new ArrayList<>()).add(entity);
            }
            for (SecurityCamera camera : registry.cameras()) {
                if (!camera.world().equals(chunk.getWorld().getName())
                        || chunk(camera.x()) != chunk.getX() || chunk(camera.z()) != chunk.getZ()) continue;
                recover(camera, found.getOrDefault(camera.id(), List.of()));
            }
        });
    }

    public boolean prepare(SecurityCamera camera, Consumer<ArmorStand> callback) {
        World world = Bukkit.getWorld(camera.world());
        if (world == null) return false;
        world.getChunkAtAsync(chunk(camera.x()), chunk(camera.z())).whenComplete((chunk, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null || chunk == null) {
                        callback.accept(null);
                        return;
                    }
                    recover(camera);
                    CameraEntities current = entities.get(camera.id());
                    callback.accept(current != null && current.valid() ? current.anchor() : null);
                }));
        return true;
    }

    public Optional<ItemDisplay> visual(long cameraId) {
        CameraEntities current = entities.get(cameraId);
        return current == null || !current.visual().isValid()
                ? Optional.empty() : Optional.of(current.visual());
    }

    public void stop() {
        for (long cameraId : List.copyOf(entities.keySet())) removeEntities(cameraId);
    }

    private void recover(SecurityCamera camera) {
        World world = Bukkit.getWorld(camera.world());
        if (world == null) return;
        List<Entity> found = world.getNearbyEntities(location(camera), 1.5, 1.5, 1.5).stream()
                .filter(entity -> cameraId(entity).filter(id -> id == camera.id()).isPresent()).toList();
        recover(camera, found);
    }

    private void recover(SecurityCamera camera, List<Entity> found) {
        ItemDisplay visual = null;
        Interaction hitbox = null;
        ArmorStand anchor = null;
        for (Entity entity : found) {
            String part = entity.getPersistentDataContainer().get(partKey, PersistentDataType.STRING);
            if (VISUAL.equals(part) && entity instanceof ItemDisplay candidate && visual == null) visual = candidate;
            else if (HITBOX.equals(part) && entity instanceof Interaction candidate && hitbox == null) hitbox = candidate;
            else if (ANCHOR.equals(part) && entity instanceof ArmorStand candidate && anchor == null) anchor = candidate;
            else entity.remove();
        }
        if (visual == null || hitbox == null || anchor == null) {
            if (visual != null) visual.remove();
            if (hitbox != null) hitbox.remove();
            if (anchor != null) anchor.remove();
            entities.put(camera.id(), spawn(camera));
            return;
        }
        entities.put(camera.id(), new CameraEntities(visual, hitbox, anchor));
        update(camera);
    }

    private CameraEntities spawn(SecurityCamera camera) {
        World world = Bukkit.getWorld(camera.world());
        if (world == null) throw new IllegalStateException("Camera world is not loaded: " + camera.world());
        Location location = location(camera);
        ItemDisplay visual = world.spawn(location, ItemDisplay.class, display -> {
            display.setItemStack(items.camera("en_US"));
            display.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(0.55f, 0.55f, 0.55f), new AxisAngle4f()));
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
            display.setInvulnerable(true);
            display.setPersistent(true);
            identify(display, camera.id(), VISUAL);
        });
        Interaction hitbox = world.spawn(location, Interaction.class, interaction -> {
            interaction.setInteractionWidth(0.8f);
            interaction.setInteractionHeight(0.8f);
            interaction.setResponsive(true);
            interaction.setInvulnerable(true);
            interaction.setPersistent(true);
            identify(interaction, camera.id(), HITBOX);
        });
        ArmorStand anchor = world.spawn(location, ArmorStand.class, stand -> {
            stand.setVisible(false);
            stand.setMarker(true);
            stand.setSmall(true);
            stand.setGravity(false);
            stand.setInvulnerable(true);
            stand.setSilent(true);
            stand.setPersistent(true);
            identify(stand, camera.id(), ANCHOR);
        });
        anchor.teleport(anchorLocation(camera, anchor));
        return new CameraEntities(visual, hitbox, anchor);
    }

    private void identify(Entity entity, long cameraId, String part) {
        entity.getPersistentDataContainer().set(cameraIdKey, PersistentDataType.LONG, cameraId);
        entity.getPersistentDataContainer().set(partKey, PersistentDataType.STRING, part);
    }

    private void removeEntities(long cameraId) {
        CameraEntities current = entities.remove(cameraId);
        if (current != null) current.remove();
    }

    private static Location location(SecurityCamera camera) {
        World world = Bukkit.getWorld(camera.world());
        if (world == null) throw new IllegalStateException("Camera world is not loaded: " + camera.world());
        return new Location(world, camera.x(), camera.y(), camera.z(), camera.yaw(), camera.pitch());
    }

    private static Location anchorLocation(SecurityCamera camera, ArmorStand anchor) {
        Location location = location(camera);
        return location.subtract(0, anchor.getEyeHeight(), 0);
    }

    private static int chunk(double coordinate) {
        return ((int) Math.floor(coordinate)) >> 4;
    }

    private record CameraEntities(ItemDisplay visual, Interaction hitbox, ArmorStand anchor) {
        private boolean valid() {
            return visual.isValid() && hitbox.isValid() && anchor.isValid();
        }

        private void remove() {
            visual.remove();
            hitbox.remove();
            anchor.remove();
        }
    }
}
