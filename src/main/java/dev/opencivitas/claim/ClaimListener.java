package dev.opencivitas.claim;

import dev.opencivitas.database.Database;
import dev.opencivitas.message.MessageService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class ClaimListener implements Listener {
    private final JavaPlugin plugin;
    private final Database database;
    private final ClaimRepository claims;
    private final ClaimRegistry registry;
    private final MessageService messages;
    private final Map<UUID, Selection> selections = new HashMap<>();

    public ClaimListener(
            JavaPlugin plugin,
            Database database,
            ClaimRepository claims,
            ClaimRegistry registry,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.claims = claims;
        this.registry = registry;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                && player.getInventory().getItemInMainHand().getType() == Material.GOLDEN_SHOVEL
                && registry.enabled(player.getWorld().getName())) {
            event.setCancelled(true);
            select(player, event.getClickedBlock());
            return;
        }
        registry.at(
                        event.getClickedBlock().getWorld().getName(),
                        event.getClickedBlock().getX(), event.getClickedBlock().getZ())
                .filter(claim -> !authorized(player, claim))
                .ifPresent(claim -> deny(player, event));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        protect(event.getPlayer(), event.getBlock(), () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        protect(event.getPlayer(), event.getBlockPlaced(), () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        protect(event.getPlayer(), event.getRightClicked().getLocation(), () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Player)) {
            protect(event.getPlayer(), event.getRightClicked().getLocation(), () -> event.setCancelled(true));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null) {
            protect(event.getPlayer(), event.getEntity().getLocation(), () -> event.setCancelled(true));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player player) {
            protect(player, event.getEntity().getLocation(), () -> event.setCancelled(true));
        } else if (registry.at(
                        event.getEntity().getWorld().getName(),
                        event.getEntity().getLocation().getBlockX(),
                        event.getEntity().getLocation().getBlockZ())
                .map(claim -> !claim.explosions()).orElse(false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player
                && (event.getEntity() instanceof ArmorStand
                || event.getEntity() instanceof Hanging
                || event.getEntity() instanceof org.bukkit.entity.Vehicle)) {
            protect(player, event.getEntity().getLocation(), () -> event.setCancelled(true));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (registry.at(event.getBlock().getWorld().getName(), event.getBlock().getX(), event.getBlock().getZ())
                .isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::explosionProtected);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::explosionProtected);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (crossesBoundary(event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (crossesBoundary(event.getBlocks(), event.getDirection())
                || crossesBoundary(event.getBlocks(), event.getDirection().getOppositeFace())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        Location source = event.getSource().getLocation();
        Location destination = event.getDestination().getLocation();
        if (source != null && destination != null && claimId(source) != claimId(destination)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (claimed(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        long destinationClaim = claimId(event.getBlock().getLocation());
        if (destinationClaim != 0 && destinationClaim != claimId(event.getSource().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFluidLevel(FluidLevelChangeEvent event) {
        Block block = event.getBlock();
        for (BlockFace face : new BlockFace[]{
                BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
            if (claimId(block.getLocation()) != claimId(block.getRelative(face).getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void select(Player player, Block clicked) {
        Selection current = selections.remove(player.getUniqueId());
        if (current instanceof CreateSelection creation) {
            if (!creation.world().equals(clicked.getWorld().getName())) {
                messages.send(player, "claims.selection-world");
                return;
            }
            complete(player, database.submit(() -> claims.create(
                    player.getUniqueId(), creation.world(), creation.x(), creation.z(),
                    clicked.getX(), clicked.getZ(), Instant.now().toEpochMilli())),
                    operation -> finishSelection(player, operation, false));
            return;
        }
        if (current instanceof ResizeSelection resize) {
            if (!resize.world().equals(clicked.getWorld().getName())) {
                messages.send(player, "claims.selection-world");
                return;
            }
            complete(player, database.submit(() -> claims.resize(
                    player.getUniqueId(), resize.claimId(), resize.fixedX(), resize.fixedZ(),
                    clicked.getX(), clicked.getZ(), Instant.now().toEpochMilli())),
                    operation -> finishSelection(player, operation, true));
            return;
        }

        Optional<LandClaim> selected = registry.at(
                clicked.getWorld().getName(), clicked.getX(), clicked.getZ());
        if (selected.isEmpty()) {
            selections.put(player.getUniqueId(), new CreateSelection(
                    clicked.getWorld().getName(), clicked.getX(), clicked.getZ()));
            messages.send(player, "claims.selection-first",
                    Placeholder.unparsed("x", Integer.toString(clicked.getX())),
                    Placeholder.unparsed("z", Integer.toString(clicked.getZ())));
            return;
        }

        LandClaim claim = selected.get();
        if (!claim.ownerId().equals(player.getUniqueId())) {
            messages.send(player, "claims.no-permission");
            return;
        }
        showBoundary(player, claim, clicked.getY() + 1);
        if (!claim.isCorner(clicked.getX(), clicked.getZ())) {
            messages.send(player, "claims.selection-click-corner");
            return;
        }
        int fixedX = clicked.getX() == claim.minX() ? claim.maxX() : claim.minX();
        int fixedZ = clicked.getZ() == claim.minZ() ? claim.maxZ() : claim.minZ();
        selections.put(player.getUniqueId(), new ResizeSelection(
                claim.id(), claim.worldName(), fixedX, fixedZ));
        messages.send(player, "claims.selection-resize",
                Placeholder.unparsed("x", Integer.toString(clicked.getX())),
                Placeholder.unparsed("z", Integer.toString(clicked.getZ())));
    }

    private void finishSelection(Player player, ClaimOperation operation, boolean resize) {
        if (operation.result() != ClaimResult.SUCCESS) {
            selectionError(player, operation.result());
            return;
        }
        LandClaim claim = operation.claim().orElseThrow();
        registry.upsert(claim);
        messages.send(player, resize ? "claims.resized" : "claims.created",
                Placeholder.unparsed("area", Integer.toString(claim.area())),
                Placeholder.unparsed("remaining", Integer.toString(operation.remainingBlocks())));
        showBoundary(player, claim, player.getLocation().getBlockY());
    }

    private void selectionError(Player player, ClaimResult result) {
        String key = switch (result) {
            case OVERLAP -> "claims.overlap";
            case INSUFFICIENT_BLOCKS -> "claims.insufficient-blocks";
            case MAX_BLOCKS -> "claims.max-blocks";
            case NO_PERMISSION -> "claims.no-permission";
            case CLAIM_NOT_FOUND -> "claims.not-found-here";
            default -> "claims.failed";
        };
        messages.send(player, key);
    }

    private void showBoundary(Player player, LandClaim claim, int y) {
        int width = claim.maxX() - claim.minX() + 1;
        int depth = claim.maxZ() - claim.minZ() + 1;
        int step = Math.max(1, Math.addExact(width, depth) / 128);
        for (int x = claim.minX(); x <= claim.maxX(); x += step) {
            particle(player, claim.worldName(), x, y, claim.minZ());
            particle(player, claim.worldName(), x, y, claim.maxZ());
        }
        for (int z = claim.minZ(); z <= claim.maxZ(); z += step) {
            particle(player, claim.worldName(), claim.minX(), y, z);
            particle(player, claim.worldName(), claim.maxX(), y, z);
        }
    }

    private static void particle(Player player, String world, int x, int y, int z) {
        if (player.getWorld().getName().equals(world)) {
            player.spawnParticle(Particle.END_ROD, x + 0.5, y + 0.2, z + 0.5, 1, 0, 0, 0, 0);
        }
    }

    private void protect(Player player, Block block, Runnable cancel) {
        protect(player, block.getLocation(), cancel);
    }

    private void protect(Player player, Location location, Runnable cancel) {
        registry.at(location.getWorld().getName(), location.getBlockX(), location.getBlockZ())
                .filter(claim -> !authorized(player, claim))
                .ifPresent(claim -> {
                    cancel.run();
                    messages.send(player, "claims.protected",
                            Placeholder.unparsed("player", claim.ownerName()));
                });
    }

    private void deny(Player player, PlayerInteractEvent event) {
        event.setCancelled(true);
        LandClaim claim = registry.at(
                event.getClickedBlock().getWorld().getName(),
                event.getClickedBlock().getX(), event.getClickedBlock().getZ()).orElseThrow();
        messages.send(player, "claims.protected", Placeholder.unparsed("player", claim.ownerName()));
    }

    private boolean authorized(Player player, LandClaim claim) {
        return player.hasPermission("opencivitas.claims.bypass") || claim.canBuild(player.getUniqueId());
    }

    private boolean explosionProtected(Block block) {
        return registry.at(block.getWorld().getName(), block.getX(), block.getZ())
                .map(claim -> !claim.explosions())
                .orElse(false);
    }

    private boolean crossesBoundary(java.util.List<Block> blocks, BlockFace direction) {
        for (Block block : blocks) {
            if (claimId(block.getLocation()) != claimId(block.getRelative(direction).getLocation())) {
                return true;
            }
        }
        return false;
    }

    private boolean claimed(Block block) {
        return registry.at(block.getWorld().getName(), block.getX(), block.getZ()).isPresent();
    }

    private long claimId(Location location) {
        return registry.at(location.getWorld().getName(), location.getBlockX(), location.getBlockZ())
                .map(LandClaim::id).orElse(0L);
    }

    private <T> void complete(Player player, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "An asynchronous claim selection failed", error);
                messages.send(player, "error.database");
                return;
            }
            success.accept(result);
        }));
    }

    private sealed interface Selection permits CreateSelection, ResizeSelection {
    }

    private record CreateSelection(String world, int x, int z) implements Selection {
    }

    private record ResizeSelection(long claimId, String world, int fixedX, int fixedZ) implements Selection {
    }
}
