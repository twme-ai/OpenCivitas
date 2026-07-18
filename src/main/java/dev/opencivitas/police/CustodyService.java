package dev.opencivitas.police;

import dev.opencivitas.database.Database;
import dev.opencivitas.message.MessageService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class CustodyService {
    private final JavaPlugin plugin;
    private final Database database;
    private final PoliceRepository police;
    private final MessageService messages;
    private final Map<UUID, PoliceArrest> active = new ConcurrentHashMap<>();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean releasing = new AtomicBoolean();
    private final double radiusSquared;

    public CustodyService(
            JavaPlugin plugin, Database database, PoliceRepository police, MessageService messages) {
        this.plugin = plugin;
        this.database = database;
        this.police = police;
        this.messages = messages;
        double radius = Math.max(1, Math.min(64,
                plugin.getConfig().getDouble("law-enforcement.jail-radius", 8)));
        radiusSquared = radius * radius;
    }

    public void start(Collection<PoliceArrest> detentions) {
        for (PoliceArrest arrest : detentions) active.put(arrest.suspectId(), arrest);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::releaseDue, 20L, 20L);
    }

    public boolean reserve(UUID suspect) {
        return !active.containsKey(suspect) && pending.add(suspect);
    }

    public void cancelReservation(UUID suspect) {
        pending.remove(suspect);
    }

    public void activate(PoliceArrest arrest) {
        pending.remove(arrest.suspectId());
        if (arrest.status() == ArrestStatus.ACTIVE) active.put(arrest.suspectId(), arrest);
    }

    public Optional<PoliceArrest> detention(UUID player) {
        return Optional.ofNullable(active.get(player));
    }

    public boolean restricted(UUID player) {
        return pending.contains(player) || active.containsKey(player);
    }

    public boolean mayMove(UUID player, Location destination) {
        if (pending.contains(player)) return false;
        if (!active.containsKey(player)) return true;
        Location jail = jail().orElse(null);
        return jail != null && jail.getWorld().equals(destination.getWorld())
                && jail.distanceSquared(destination) <= radiusSquared;
    }

    public void confine(Player player) {
        if (!active.containsKey(player.getUniqueId())) return;
        jail().ifPresent(location -> player.teleportAsync(location).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Could not teleport detained citizen to jail", error);
            return false;
        }));
    }

    public Optional<Location> jail() {
        return Optional.ofNullable(plugin.getConfig().getLocation("law-enforcement.jail"))
                .filter(location -> location.getWorld() != null);
    }

    public Optional<Location> release() {
        return Optional.ofNullable(plugin.getConfig().getLocation("law-enforcement.release"))
                .filter(location -> location.getWorld() != null);
    }

    public void setJail(Location location) {
        plugin.getConfig().set("law-enforcement.jail", location);
        plugin.saveConfig();
    }

    public void setRelease(Location location) {
        plugin.getConfig().set("law-enforcement.release", location);
        plugin.saveConfig();
    }

    private void releaseDue() {
        if (!releasing.compareAndSet(false, true)) return;
        long now = System.currentTimeMillis();
        database.submit(() -> police.releaseDue(now)).whenComplete((released, error) -> {
            releasing.set(false);
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "Could not release due prisoners", error);
                        return;
                    }
                    for (PoliceArrest arrest : released) release(arrest);
                });
        });
    }

    private void release(PoliceArrest arrest) {
        active.remove(arrest.suspectId());
        Player player = Bukkit.getPlayer(arrest.suspectId());
        if (player == null) return;
        messages.send(player, "police.released");
        release().ifPresent(location -> player.teleportAsync(location).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Could not teleport released citizen", error);
            return false;
        }));
    }

    public void sendRemaining(Player player) {
        PoliceArrest arrest = active.get(player.getUniqueId());
        if (arrest == null) return;
        long seconds = Math.max(0, Duration.between(
                java.time.Instant.now(), arrest.releaseAt()).toSeconds());
        messages.send(player, "police.detained-remaining",
                Placeholder.unparsed("seconds", Long.toString(seconds)),
                Placeholder.unparsed("id", Long.toString(arrest.id())));
    }
}
