package dev.opencivitas.navigation;

import dev.opencivitas.message.MessageService;
import dev.opencivitas.property.Property;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NavigationService implements Listener {
    private final JavaPlugin plugin;
    private final MessageService messages;
    private final long updateTicks;
    private final Map<UUID, GpsTarget> targets = new ConcurrentHashMap<>();

    public NavigationService(JavaPlugin plugin, MessageService messages, long updateTicks) {
        this.plugin = plugin;
        this.messages = messages;
        this.updateTicks = updateTicks;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::update, updateTicks, updateTicks);
    }

    public boolean start(Player player, Property property) {
        World world = Bukkit.getWorld(property.worldName());
        if (world == null) return false;
        Location target = propertyCenter(world, property);
        targets.put(player.getUniqueId(), new GpsTarget(property.plotId(), target));
        player.setCompassTarget(target);
        return true;
    }

    public boolean stop(Player player) {
        boolean removed = targets.remove(player.getUniqueId()) != null;
        player.setCompassTarget(player.getWorld().getSpawnLocation());
        return removed;
    }

    public NavigationRoute route(Player player, Property property) {
        World world = Bukkit.getWorld(property.worldName());
        if (world == null) return null;
        return route(player.getLocation(), propertyCenter(world, property));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        targets.remove(event.getPlayer().getUniqueId());
    }

    private void update() {
        targets.forEach((playerId, target) -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                targets.remove(playerId);
                return;
            }
            if (!player.getWorld().equals(target.location().getWorld())) {
                player.sendActionBar(messages.component(player, "navigation.gps-other-world",
                        Placeholder.unparsed("plot", target.plotId()),
                        Placeholder.unparsed("world", target.location().getWorld().getName())));
                return;
            }
            NavigationRoute route = route(player.getLocation(), target.location());
            player.sendActionBar(messages.component(player, "navigation.gps-actionbar",
                    Placeholder.unparsed("plot", target.plotId()),
                    Placeholder.unparsed("distance", Long.toString(Math.round(route.distance()))),
                    Placeholder.component("direction", messages.component(player,
                            "navigation.direction." + route.direction()))));
        });
    }

    private static NavigationRoute route(Location from, Location to) {
        return RouteCalculator.route(from.getX(), from.getZ(), to.getX(), to.getZ());
    }

    private static Location propertyCenter(World world, Property property) {
        return new Location(world,
                (property.minX() + property.maxX()) / 2.0 + 0.5,
                property.maxY() + 1.0,
                (property.minZ() + property.maxZ()) / 2.0 + 0.5);
    }

    private record GpsTarget(String plotId, Location location) {
        private GpsTarget {
            location = location.clone();
        }
    }
}
