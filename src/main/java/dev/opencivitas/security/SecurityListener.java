package dev.opencivitas.security;

import dev.opencivitas.database.Database;
import dev.opencivitas.message.MessageService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class SecurityListener implements Listener {
    private final JavaPlugin plugin;
    private final Database database;
    private final SecurityRepository security;
    private final SecurityRegistry registry;
    private final SecurityItems items;
    private final CameraManager cameraManager;
    private final CameraViewService views;
    private final SecurityMenuService menus;
    private final MessageService messages;
    private final Set<String> pendingComputers = new HashSet<>();
    private final Set<Long> pendingCameras = new HashSet<>();

    public SecurityListener(
            JavaPlugin plugin,
            Database database,
            SecurityRepository security,
            SecurityRegistry registry,
            SecurityItems items,
            CameraManager cameraManager,
            CameraViewService views,
            SecurityMenuService menus,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.security = security;
        this.registry = registry;
        this.items = items;
        this.cameraManager = cameraManager;
        this.views = views;
        this.menus = menus;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack placed = event.getItemInHand();
        if (items.isCamera(placed)) {
            event.setCancelled(true);
            placeCamera(event);
            return;
        }
        if (!items.isComputer(placed)) return;
        Block block = event.getBlockPlaced();
        String key = key(block);
        if (registry.computerAt(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()).isPresent()
                || !pendingComputers.add(key)) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "security.location-occupied");
            return;
        }
        UUID owner = event.getPlayer().getUniqueId();
        database.submit(() -> security.placeComputer(owner, block.getWorld().getName(),
                        block.getX(), block.getY(), block.getZ(), System.currentTimeMillis()))
                .whenComplete((operation, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    pendingComputers.remove(key);
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Could not place security computer", error);
                        refundComputer(event.getPlayer(), block);
                        return;
                    }
                    if (operation.result() != SecurityResult.SUCCESS) {
                        refundComputer(event.getPlayer(), block);
                        sendResult(event.getPlayer(), operation.result());
                        return;
                    }
                    SecurityComputer computer = operation.value().orElseThrow();
                    registry.upsert(computer);
                    messages.send(event.getPlayer(), "security.computer-placed",
                            Placeholder.unparsed("computer", computer.name()));
                }));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
        Block block = event.getClickedBlock();
        SecurityComputer computer = registry.computerAt(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ()).orElse(null);
        if (computer == null) return;
        event.setCancelled(true);
        menus.openComputer(event.getPlayer(), computer.id());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Long cameraId = cameraManager.cameraId(event.getRightClicked()).orElse(null);
        if (cameraId == null) return;
        event.setCancelled(true);
        SecurityCamera camera = registry.camera(cameraId).orElse(null);
        if (camera == null) {
            event.getRightClicked().remove();
            return;
        }
        if (!camera.ownerId().equals(event.getPlayer().getUniqueId())) {
            messages.send(event.getPlayer(), "security.not-owner");
            return;
        }
        menus.openCamera(event.getPlayer(), camera);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        String key = key(block);
        if (pendingComputers.contains(key)) {
            event.setCancelled(true);
            return;
        }
        SecurityComputer computer = registry.computerAt(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ()).orElse(null);
        if (computer == null) return;
        event.setCancelled(true);
        if (!computer.ownerId().equals(event.getPlayer().getUniqueId())) {
            messages.send(event.getPlayer(), "security.not-owner");
            return;
        }
        database.submit(() -> security.deleteComputer(event.getPlayer().getUniqueId(), computer.id()))
                .whenComplete((operation, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Could not remove security computer", error);
                        messages.send(event.getPlayer(), "error.database");
                        return;
                    }
                    if (operation.result() != SecurityResult.SUCCESS) {
                        sendResult(event.getPlayer(), operation.result());
                        return;
                    }
                    block.setType(Material.AIR, false);
                    registry.removeComputer(computer.id());
                    giveOrDrop(event.getPlayer(), items.computer(messages.locale(event.getPlayer())), block.getLocation());
                    messages.send(event.getPlayer(), "security.computer-removed",
                            Placeholder.unparsed("computer", computer.name()));
                }));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        Long cameraId = cameraManager.cameraId(event.getEntity()).orElse(null);
        if (cameraId == null) return;
        event.setCancelled(true);
        if (!(event.getDamager() instanceof Player player)) return;
        SecurityCamera camera = registry.camera(cameraId).orElse(null);
        if (camera == null) {
            event.getEntity().remove();
            return;
        }
        if (!camera.ownerId().equals(player.getUniqueId())) {
            messages.send(player, "security.not-owner");
            return;
        }
        if (!pendingCameras.add(camera.id())) return;
        Location drop = event.getEntity().getLocation();
        database.submit(() -> security.deleteCamera(player.getUniqueId(), camera.id()))
                .whenComplete((operation, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    pendingCameras.remove(camera.id());
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Could not remove security camera", error);
                        messages.send(player, "error.database");
                        return;
                    }
                    if (operation.result() != SecurityResult.SUCCESS) {
                        sendResult(player, operation.result());
                        return;
                    }
                    views.stopViewers(camera.id());
                    cameraManager.remove(camera.id());
                    giveOrDrop(player, items.camera(messages.locale(player)), drop);
                    messages.send(player, "security.camera-removed",
                            Placeholder.unparsed("camera", camera.name()));
                }));
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        cameraManager.onChunkLoad(event.getChunk());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (movesComputer(event.getBlocks())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (movesComputer(event.getBlocks())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(this::computer);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(this::computer);
    }

    private void placeCamera(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block against = event.getBlockAgainst();
        BlockFace face = against.getFace(event.getBlockPlaced());
        if (face == null) face = BlockFace.UP;
        Location location = against.getLocation().add(0.5, 0.5, 0.5)
                .add(face.getDirection().multiply(0.62));
        float yaw = yaw(face, player.getLocation().getYaw());
        float pitch = face == BlockFace.UP ? -45 : face == BlockFace.DOWN ? 45 : 0;
        consume(player, event.getHand());
        database.submit(() -> security.placeCamera(player.getUniqueId(), against.getWorld().getName(),
                        location.getX(), location.getY(), location.getZ(), yaw, pitch, System.currentTimeMillis()))
                .whenComplete((operation, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Could not place security camera", error);
                        refund(player, items.camera(messages.locale(player)), player.getLocation());
                        return;
                    }
                    if (operation.result() != SecurityResult.SUCCESS) {
                        refund(player, items.camera(messages.locale(player)), player.getLocation());
                        sendResult(player, operation.result());
                        return;
                    }
                    SecurityCamera camera = operation.value().orElseThrow();
                    cameraManager.place(camera);
                    messages.send(player, "security.camera-placed",
                            Placeholder.unparsed("camera", camera.name()));
                }));
    }

    private void refundComputer(Player player, Block block) {
        if (block.getType() == Material.NETHER_BRICK_STAIRS) block.setType(Material.AIR, false);
        refund(player, items.computer(messages.locale(player)), block.getLocation());
    }

    private boolean movesComputer(List<Block> blocks) {
        return blocks.stream().anyMatch(this::computer);
    }

    private boolean computer(Block block) {
        return pendingComputers.contains(key(block)) || registry.computerAt(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ()).isPresent();
    }

    private void sendResult(Player player, SecurityResult result) {
        messages.send(player, "security.result." + result.name().toLowerCase(Locale.ROOT));
    }

    private static void consume(Player player, EquipmentSlot hand) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        ItemStack held = hand == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand() : player.getInventory().getItemInOffHand();
        held.setAmount(Math.max(0, held.getAmount() - 1));
    }

    private static void giveOrDrop(Player player, ItemStack item, Location location) {
        if (player.isOnline()) {
            var remaining = player.getInventory().addItem(item);
            if (remaining.isEmpty()) return;
            for (ItemStack overflow : remaining.values()) location.getWorld().dropItemNaturally(location, overflow);
        } else {
            location.getWorld().dropItemNaturally(location, item);
        }
    }

    private static void refund(Player player, ItemStack item, Location location) {
        if (player.getGameMode() != GameMode.CREATIVE) giveOrDrop(player, item, location);
    }

    private static float yaw(BlockFace face, float playerYaw) {
        return switch (face) {
            case SOUTH -> 0;
            case WEST -> 90;
            case NORTH -> 180;
            case EAST -> -90;
            default -> playerYaw;
        };
    }

    private static String key(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }
}
