package dev.opencivitas.job;

import dev.opencivitas.database.Database;
import dev.opencivitas.economy.Money;
import dev.opencivitas.message.MessageService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
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
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class JobEarningService implements Listener {
    private final JavaPlugin plugin;
    private final Database database;
    private final JobEarningRepository earnings;
    private final JobEarningPolicy policy;
    private final MessageService messages;
    private final String currencySymbol;
    private final AtomicBoolean settling = new AtomicBoolean();
    private final Set<JobBlockPosition> placedBlocks = new HashSet<>();
    private BukkitTask settlementTask;

    public JobEarningService(
            JavaPlugin plugin,
            Database database,
            JobEarningRepository earnings,
            JobEarningPolicy policy,
            MessageService messages,
            String currencySymbol
    ) {
        this.plugin = plugin;
        this.database = database;
        this.earnings = earnings;
        this.policy = policy;
        this.messages = messages;
        this.currencySymbol = currencySymbol;
    }

    public void start() throws SQLException {
        placedBlocks.addAll(earnings.placedBlocks());
        settleDue();
        long frequency = Math.max(20, Math.min(600, policy.payoutIntervalMillis() / 200));
        settlementTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::settleDue, frequency, frequency);
    }

    public void stop() {
        if (settlementTask != null) settlementTask.cancel();
        settlementTask = null;
        placedBlocks.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        String material = block.getType().name();
        if (!policy.hasRules(JobActionType.BREAK, material)) return;
        JobBlockPosition position = position(block);
        placedBlocks.add(position);
        UUID playerId = event.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        submitMaintenance(() -> earnings.markPlacedBlock(
                position, material, playerId, now), "record a placed earning block");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        String target = block.getType().name();
        if (!policy.hasRules(JobActionType.BREAK, target)) return;
        long now = System.currentTimeMillis();
        List<JobEarningCandidate> candidates = eligible(event.getPlayer())
                ? policy.roll(JobActionType.BREAK, target, ThreadLocalRandom.current())
                : List.of();
        JobBlockPosition position = position(block);
        boolean placed = placedBlocks.remove(position);
        if (candidates.isEmpty() && !placed) return;
        UUID playerId = event.getPlayer().getUniqueId();
        database.submit(() -> earnings.accrueBreak(
                playerId, position, candidates, now, policy.nextPayoutAt(now), placed))
                .whenComplete((accrual, error) -> completeAccrual(playerId, accrual, error));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        Player player = event.getEntity().getKiller();
        if (player == null || !eligible(player) || event.getEntity() instanceof Player
                || event.getEntity() instanceof ArmorStand || event.getEntity().hasMetadata("NPC")) {
            return;
        }
        String target = event.getEntityType().name();
        if (!policy.hasRules(JobActionType.KILL, target)) return;
        List<JobEarningCandidate> candidates = policy.roll(
                JobActionType.KILL, target, ThreadLocalRandom.current());
        if (candidates.isEmpty()) return;
        long now = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();
        database.submit(() -> earnings.accrue(
                playerId, candidates, now, policy.nextPayoutAt(now)))
                .whenComplete((accrual, error) -> completeAccrual(playerId, accrual, error));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        move(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        move(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        clear(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        clear(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        Block block = event.getBlock();
        if (!policy.hasRules(JobActionType.BREAK, block.getType().name())) return;
        JobBlockPosition position = position(block);
        placedBlocks.remove(position);
        submitMaintenance(() -> earnings.clearPlacedBlocks(List.of(position)),
                "clear a burned earning block");
    }

    private void move(List<Block> blocks, BlockFace direction) {
        List<JobBlockMove> moves = new ArrayList<>();
        for (Block block : blocks) {
            String material = block.getType().name();
            if (!policy.hasRules(JobActionType.BREAK, material)) continue;
            moves.add(new JobBlockMove(
                    position(block), position(block.getRelative(direction)), material));
        }
        if (moves.isEmpty()) return;
        for (JobBlockMove move : moves) {
            placedBlocks.remove(move.source());
            placedBlocks.add(move.destination());
        }
        long now = System.currentTimeMillis();
        submitMaintenance(() -> earnings.moveBlocks(moves, now), "move earning block markers");
    }

    private void clear(List<Block> blocks) {
        List<JobBlockPosition> positions = blocks.stream()
                .filter(block -> policy.hasRules(JobActionType.BREAK, block.getType().name()))
                .map(JobEarningService::position).toList();
        if (positions.isEmpty()) return;
        placedBlocks.removeAll(positions);
        submitMaintenance(() -> earnings.clearPlacedBlocks(positions),
                "clear destroyed earning block markers");
    }

    private void settleDue() {
        if (!settling.compareAndSet(false, true)) return;
        database.submit(() -> earnings.settleDue(System.currentTimeMillis()))
                .whenComplete((payouts, error) -> {
                    settling.set(false);
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null) {
                            plugin.getLogger().log(Level.SEVERE, "Could not settle job earnings", error);
                            return;
                        }
                        for (JobPayout payout : payouts) {
                            Player player = Bukkit.getPlayer(payout.playerId());
                            if (player == null) continue;
                            messages.send(player, "jobs.earnings-paid",
                                    Placeholder.unparsed("amount", Money.format(
                                            payout.amountCents(), currencySymbol)),
                                    Placeholder.unparsed("actions", Integer.toString(payout.actionCount())),
                                    Placeholder.unparsed("balance", Money.format(
                                            payout.balanceCents(), currencySymbol)));
                        }
                    });
                });
    }

    private void completeAccrual(UUID playerId, JobEarningAccrual accrual, Throwable error) {
        if (error != null) {
            if (plugin.isEnabled()) {
                plugin.getLogger().log(Level.WARNING, "Could not record job earnings", error);
            }
            return;
        }
        if (!plugin.isEnabled() || accrual.amountCents() <= 0) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) return;
            player.sendActionBar(messages.component(player, "jobs.earnings-queued",
                    Placeholder.unparsed("amount", Money.format(accrual.amountCents(), currencySymbol))));
        });
    }

    private void submitMaintenance(SqlAction action, String description) {
        database.submit(() -> {
            action.run();
            return null;
        }).exceptionally(error -> {
            if (plugin.isEnabled()) {
                plugin.getLogger().log(Level.WARNING, "Could not " + description, error);
            }
            return null;
        });
    }

    private static boolean eligible(Player player) {
        return player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
    }

    private static JobBlockPosition position(Block block) {
        return new JobBlockPosition(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws Exception;
    }
}
