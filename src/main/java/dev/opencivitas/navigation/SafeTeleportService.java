package dev.opencivitas.navigation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Consumer;

public final class SafeTeleportService {
    private final JavaPlugin plugin;

    public SafeTeleportService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void teleport(Player player, SavedLocation saved, Consumer<TeleportOutcome> completion) {
        World world = Bukkit.getWorld(saved.world());
        if (world == null) {
            completion.accept(TeleportOutcome.WORLD_UNAVAILABLE);
            return;
        }
        Location destination = new Location(world, saved.x(), saved.y(), saved.z(), saved.yaw(), saved.pitch());
        if (!world.getWorldBorder().isInside(destination)) {
            completion.accept(TeleportOutcome.UNSAFE_DESTINATION);
            return;
        }
        world.getChunkAtAsync(destination).whenComplete((chunk, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null || !safe(destination)) {
                        completion.accept(TeleportOutcome.UNSAFE_DESTINATION);
                        return;
                    }
                    if (!player.isOnline()) {
                        completion.accept(TeleportOutcome.FAILED);
                        return;
                    }
                    player.teleportAsync(destination).whenComplete((teleported, teleportError) ->
                            Bukkit.getScheduler().runTask(plugin, () -> completion.accept(
                                    teleportError == null && teleported
                                            ? TeleportOutcome.SUCCESS : TeleportOutcome.FAILED)));
                }));
    }

    private static boolean safe(Location location) {
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block floor = feet.getRelative(0, -1, 0);
        return feet.isPassable() && head.isPassable() && floor.getType().isSolid();
    }
}
