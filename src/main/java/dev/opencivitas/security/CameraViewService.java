package dev.opencivitas.security;

import dev.opencivitas.message.MessageService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class CameraViewService implements Listener {
    private final JavaPlugin plugin;
    private final SecurityRegistry registry;
    private final CameraManager cameras;
    private final SecurityPolicy policy;
    private final MessageService messages;
    private final NamespacedKey recoveryKey;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> transitioning = new HashSet<>();
    private final Set<UUID> pending = new HashSet<>();
    private BukkitTask timeoutTask;

    public CameraViewService(
            JavaPlugin plugin,
            SecurityRegistry registry,
            CameraManager cameras,
            SecurityPolicy policy,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.registry = registry;
        this.cameras = cameras;
        this.policy = policy;
        this.messages = messages;
        recoveryKey = new NamespacedKey(plugin, "security_view_recovery");
    }

    public void start() {
        timeoutTask = Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 20L, 20L);
    }

    public boolean view(Player player, List<SecurityCamera> available, long cameraId) {
        if (sessions.containsKey(player.getUniqueId()) || !pending.add(player.getUniqueId())) return false;
        List<Long> ids = available.stream().map(SecurityCamera::id).distinct().toList();
        int index = ids.indexOf(cameraId);
        SecurityCamera camera = registry.camera(cameraId).orElse(null);
        if (camera == null || index < 0) {
            pending.remove(player.getUniqueId());
            return false;
        }
        if (!cameras.prepare(camera, anchor -> begin(player, camera, ids, anchor))) {
            pending.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public boolean switchCamera(Player player, int direction) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.cameraIds.size() < 2 || pending.contains(player.getUniqueId())) return false;
        List<SecurityCamera> candidates = new ArrayList<>();
        for (long id : session.cameraIds) registry.camera(id).ifPresent(candidates::add);
        if (candidates.size() < 2) return false;
        int current = 0;
        for (int index = 0; index < candidates.size(); index++) {
            if (candidates.get(index).id() == session.cameraId) current = index;
        }
        int selected = Math.floorMod(current + direction, candidates.size());
        SecurityCamera camera = candidates.get(selected);
        pending.add(player.getUniqueId());
        if (!cameras.prepare(camera, anchor -> change(player, session, camera, anchor))) {
            pending.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public boolean exit(Player player) {
        return end(player, false);
    }

    public void stopViewers(long cameraId) {
        List<Player> affected = sessions.values().stream()
                .filter(session -> session.cameraId == cameraId)
                .map(session -> Bukkit.getPlayer(session.playerId))
                .filter(java.util.Objects::nonNull).toList();
        for (Player player : affected) end(player, false);
    }

    public void stop() {
        if (timeoutTask != null) timeoutTask.cancel();
        for (UUID playerId : List.copyOf(sessions.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) end(player, false);
        }
        pending.clear();
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking() || !sessions.containsKey(event.getPlayer().getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> end(event.getPlayer(), false));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (sessions.containsKey(playerId) && !transitioning.contains(playerId)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (!sessions.containsKey(playerId) || transitioning.contains(playerId)
                || event.getNewGameMode() == GameMode.SPECTATOR) return;
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> end(event.getPlayer(), false));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        end(event.getPlayer(), false);
        pending.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        String encoded = event.getPlayer().getPersistentDataContainer()
                .get(recoveryKey, PersistentDataType.STRING);
        if (encoded == null) return;
        event.getPlayer().getPersistentDataContainer().remove(recoveryKey);
        Recovery recovery = Recovery.decode(encoded);
        if (recovery == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> restore(event.getPlayer(), recovery));
    }

    private void begin(
            Player player,
            SecurityCamera camera,
            List<Long> cameraIds,
            ArmorStand anchor
    ) {
        pending.remove(player.getUniqueId());
        if (anchor == null || !player.isOnline() || sessions.containsKey(player.getUniqueId())
                || registry.camera(camera.id()).isEmpty()) return;
        Recovery recovery = new Recovery(player.getGameMode(), player.getLocation().clone());
        player.getPersistentDataContainer().set(
                recoveryKey, PersistentDataType.STRING, recovery.encode());
        Session session = new Session(player.getUniqueId(), cameraIds, camera.id(), recovery,
                System.currentTimeMillis() + policy.maximumViewDuration().toMillis());
        sessions.put(player.getUniqueId(), session);
        try {
            transition(player, camera, anchor);
        } catch (RuntimeException exception) {
            sessions.remove(player.getUniqueId());
            restore(player, recovery);
            plugin.getLogger().log(Level.WARNING, "Could not open security camera feed", exception);
            messages.send(player, "security.view-unavailable");
            return;
        }
        messages.send(player, "security.view-connected", Placeholder.unparsed("camera", camera.name()));
    }

    private void change(Player player, Session session, SecurityCamera camera, ArmorStand anchor) {
        pending.remove(player.getUniqueId());
        if (anchor == null || !player.isOnline() || sessions.get(player.getUniqueId()) != session) return;
        cameras.visual(session.cameraId).ifPresent(visual -> player.showEntity(plugin, visual));
        session.cameraId = camera.id();
        try {
            transition(player, camera, anchor);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not switch security camera feed", exception);
            end(player, false);
            return;
        }
        messages.send(player, "security.view-switched", Placeholder.unparsed("camera", camera.name()));
    }

    private void transition(Player player, SecurityCamera camera, ArmorStand anchor) {
        UUID playerId = player.getUniqueId();
        transitioning.add(playerId);
        try {
            player.leaveVehicle();
            if (player.getGameMode() != GameMode.SPECTATOR) player.setGameMode(GameMode.SPECTATOR);
            player.setSpectatorTarget(null);
            player.teleport(anchor.getLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN);
            player.setSpectatorTarget(anchor);
            cameras.visual(camera.id()).ifPresent(visual -> player.hideEntity(plugin, visual));
        } finally {
            transitioning.remove(playerId);
        }
    }

    private boolean end(Player player, boolean timedOut) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) return false;
        pending.remove(player.getUniqueId());
        cameras.visual(session.cameraId).ifPresent(visual -> player.showEntity(plugin, visual));
        restore(player, session.recovery);
        messages.send(player, timedOut ? "security.view-timeout" : "security.view-disconnected");
        return true;
    }

    private void restore(Player player, Recovery recovery) {
        UUID playerId = player.getUniqueId();
        transitioning.add(playerId);
        try {
            if (player.getGameMode() == GameMode.SPECTATOR) player.setSpectatorTarget(null);
            player.setGameMode(recovery.gameMode());
            player.teleport(recovery.location(), PlayerTeleportEvent.TeleportCause.PLUGIN);
            player.getPersistentDataContainer().remove(recoveryKey);
        } finally {
            transitioning.remove(playerId);
        }
    }

    private void expire() {
        long now = System.currentTimeMillis();
        for (Session session : List.copyOf(sessions.values())) {
            if (session.expiresAt > now) continue;
            Player player = Bukkit.getPlayer(session.playerId);
            if (player != null) end(player, true);
        }
    }

    private static final class Session {
        private final UUID playerId;
        private final List<Long> cameraIds;
        private long cameraId;
        private final Recovery recovery;
        private final long expiresAt;

        private Session(
                UUID playerId, List<Long> cameraIds, long cameraId,
                Recovery recovery, long expiresAt) {
            this.playerId = playerId;
            this.cameraIds = List.copyOf(cameraIds);
            this.cameraId = cameraId;
            this.recovery = recovery;
            this.expiresAt = expiresAt;
        }
    }

    private record Recovery(GameMode gameMode, Location location) {
        private String encode() {
            return gameMode.name() + ';' + location.getWorld().getUID() + ';'
                    + location.getX() + ';' + location.getY() + ';' + location.getZ() + ';'
                    + location.getYaw() + ';' + location.getPitch();
        }

        private static Recovery decode(String value) {
            String[] parts = value.split(";", -1);
            if (parts.length != 7) return null;
            try {
                GameMode mode = GameMode.valueOf(parts[0]);
                World world = Bukkit.getWorld(UUID.fromString(parts[1]));
                if (world == null) return null;
                Location location = new Location(world,
                        Double.parseDouble(parts[2]), Double.parseDouble(parts[3]),
                        Double.parseDouble(parts[4]), Float.parseFloat(parts[5]), Float.parseFloat(parts[6]));
                return new Recovery(mode, location);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }
    }
}
