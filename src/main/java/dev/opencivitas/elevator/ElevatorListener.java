package dev.opencivitas.elevator;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import dev.opencivitas.message.MessageService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.util.BoundingBox;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

public final class ElevatorListener implements Listener {
    private static final double FLOOR_TOLERANCE = 0.01;
    private static final double STANDING_HEIGHT = 1.8;
    private static final double PLAYER_WIDTH = 0.6;

    private final ElevatorPolicy policy;
    private final MessageService messages;
    private final Map<UUID, Long> readyAt = new HashMap<>();

    public ElevatorListener(ElevatorPolicy policy, MessageService messages) {
        this.policy = policy;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onJump(PlayerJumpEvent event) {
        use(event.getPlayer(), event.getFrom(), 1);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking()) use(event.getPlayer(), event.getPlayer().getLocation(), -1);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        readyAt.remove(event.getPlayer().getUniqueId());
    }

    private void use(Player player, Location origin, int direction) {
        World world = origin.getWorld();
        if (!policy.enabledIn(world)
                || !player.hasPermission("opencivitas.elevators.use")
                || player.isDead()
                || player.isInsideVehicle()
                || player.isFlying()) {
            return;
        }

        int floorY = (int) Math.floor(origin.getY() - FLOOR_TOLERANCE);
        Block floor = world.getBlockAt(origin.getBlockX(), floorY, origin.getBlockZ());
        if (floor.getType() != policy.floorMaterial()) return;

        OptionalInt targetY = ElevatorSearch.nextFloor(
                floorY,
                world.getMinHeight(),
                world.getMaxHeight(),
                direction,
                policy.maximumDistance(),
                y -> world.getBlockAt(floor.getX(), y, floor.getZ()).getType() == policy.floorMaterial());
        if (targetY.isEmpty()) return;

        long now = System.nanoTime();
        if (readyAt.getOrDefault(player.getUniqueId(), 0L) > now) return;
        readyAt.put(player.getUniqueId(), now + policy.cooldownNanos());

        Location destination = new Location(
                world,
                floor.getX() + 0.5,
                targetY.getAsInt() + 1.0,
                floor.getZ() + 0.5,
                player.getYaw(),
                player.getPitch());
        if (!safe(player, destination)) {
            player.sendActionBar(messages.component(player, "elevator.destination-obstructed"));
            return;
        }

        // The source and target share a loaded chunk, so a synchronous same-column teleport cannot load terrain.
        if (!player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            player.sendActionBar(messages.component(player, "elevator.teleport-failed"));
            return;
        }
        player.setFallDistance(0);
    }

    private static boolean safe(Player player, Location destination) {
        World world = destination.getWorld();
        if (destination.getY() < world.getMinHeight()
                || destination.getY() + STANDING_HEIGHT > world.getMaxHeight()
                || !world.getWorldBorder().isInside(destination)) {
            return false;
        }
        Block feet = destination.getBlock();
        if (!feet.isPassable() || !feet.getRelative(0, 1, 0).isPassable()) return false;

        double halfWidth = Math.max(PLAYER_WIDTH, player.getWidth()) / 2.0;
        double height = Math.max(STANDING_HEIGHT, player.getHeight());
        BoundingBox box = new BoundingBox(
                destination.getX() - halfWidth,
                destination.getY(),
                destination.getZ() - halfWidth,
                destination.getX() + halfWidth,
                destination.getY() + height,
                destination.getZ() + halfWidth);
        return !player.wouldCollideUsing(box);
    }
}
