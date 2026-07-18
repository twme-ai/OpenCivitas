package dev.opencivitas.police;

import dev.opencivitas.database.Database;
import dev.opencivitas.message.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class PoliceListener implements Listener {
    private final JavaPlugin plugin;
    private final Database database;
    private final PoliceRepository police;
    private final CustodyService custody;
    private final MessageService messages;
    private final NamespacedKey clueKey;
    private final NamespacedKey evidenceKey;
    private final Set<Long> pendingClues = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> containmentNotices = new ConcurrentHashMap<>();
    private final Map<UUID, Long> clueNotices = new ConcurrentHashMap<>();

    public PoliceListener(
            JavaPlugin plugin,
            Database database,
            PoliceRepository police,
            CustodyService custody,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.police = police;
        this.custody = custody;
        this.messages = messages;
        clueKey = new NamespacedKey(plugin, "forensic-clue-id");
        evidenceKey = new NamespacedKey(plugin, "forensic-evidence-id");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        UUID attackerId = attacker.getUniqueId();
        UUID victimId = victim.getUniqueId();
        Location location = victim.getLocation();
        String world = location.getWorld().getName();
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        byte[] weapon = attacker.getInventory().getItemInMainHand().getType() == Material.AIR
                ? null : attacker.getInventory().getItemInMainHand().serializeAsBytes();
        int damage = (int) Math.min(Integer.MAX_VALUE,
                Math.max(0, Math.round(event.getFinalDamage() * 500)));
        String cause = event.getCause().name();
        long now = System.currentTimeMillis();
        database.submit(() -> police.recordAttack(
                attackerId, victimId, world, x, y, z, damage,
                cause, weapon, now)).whenComplete((operation, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Could not record a combat incident", error);
                        return;
                    }
                    if (operation.result() != LawResult.SUCCESS
                            || !operation.value().orElseThrow().fightStarted()) return;
                    if (attacker.isOnline()) messages.send(attacker, "police.fight-started-you");
                    if (victim.isOnline()) messages.send(victim, "police.fight-started-other",
                            Placeholder.unparsed("player", attacker.getName()));
                }));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        UUID killerId = killer.getUniqueId();
        UUID victimId = victim.getUniqueId();
        Location location = victim.getLocation().clone();
        String world = location.getWorld().getName();
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        long now = System.currentTimeMillis();
        database.submit(() -> police.recordDeath(
                killerId, victimId, world, x, y, z, now))
                .whenComplete((operation, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "Could not record a player death", error);
                        return;
                    }
                    if (operation.result() != LawResult.SUCCESS) return;
                    operation.value().orElseThrow().clue().ifPresent(clue -> {
                        dropClue(location, clue.id(), messages.component(victim, "police.clue-name",
                                Placeholder.unparsed("id", Long.toString(clue.id()))));
                        if (victim.isOnline()) messages.send(victim, "police.report-available");
                    });
                }));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        Long clueId = clueId(event.getItem());
        if (clueId == null) return;
        event.setCancelled(true);
        if (!(event.getEntity() instanceof Player player)) return;
        long now = System.currentTimeMillis();
        if (now - clueNotices.getOrDefault(player.getUniqueId(), 0L) < 2_000
                || !pendingClues.add(clueId)) return;
        UUID playerId = player.getUniqueId();
        Item worldItem = event.getItem();
        database.submit(() -> police.collectClue(clueId, playerId, now))
                .whenComplete((operation, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    pendingClues.remove(clueId);
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Could not collect forensic evidence", error);
                        messages.send(player, "error.database");
                        return;
                    }
                    if (operation.result() == LawResult.NOT_AUTHORIZED) {
                        clueNotices.put(playerId, System.currentTimeMillis());
                        messages.send(player, "police.clue-police-only");
                        return;
                    }
                    if (operation.result() != LawResult.SUCCESS) {
                        if (worldItem.isValid()) worldItem.remove();
                        messages.send(player, "police.error.clue-unavailable");
                        return;
                    }
                    if (worldItem.isValid()) worldItem.remove();
                    ItemStack evidence = sealedEvidence(player, clueId);
                    Map<Integer, ItemStack> excess = player.getInventory().addItem(evidence);
                    for (ItemStack item : excess.values()) player.getWorld().dropItem(player.getLocation(), item);
                    messages.send(player, "police.clue-collected",
                            Placeholder.unparsed("id", Long.toString(clueId)));
                }));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (clueId(event.getItem()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClueDespawn(ItemDespawnEvent event) {
        if (clueId(event.getEntity()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!changedBlock(event.getFrom(), event.getTo())) return;
        if (!custody.mayMove(event.getPlayer().getUniqueId(), event.getTo())) {
            event.setTo(event.getFrom());
            containmentNotice(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!custody.mayMove(event.getPlayer().getUniqueId(), event.getTo())) {
            event.setCancelled(true);
            containmentNotice(event.getPlayer());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            custody.confine(event.getPlayer());
            custody.sendRemaining(event.getPlayer());
        });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (custody.detention(event.getPlayer().getUniqueId()).isPresent()) {
            custody.jail().ifPresent(event::setRespawnLocation);
        }
    }

    private void dropClue(Location location, long clueId, Component name) {
        ItemStack clue = new ItemStack(Material.PAPER);
        clue.editMeta(meta -> {
            meta.displayName(name);
            meta.getPersistentDataContainer().set(clueKey, PersistentDataType.LONG, clueId);
        });
        Item item = location.getWorld().dropItemNaturally(location, clue);
        item.setUnlimitedLifetime(true);
        item.setInvulnerable(true);
    }

    private ItemStack sealedEvidence(Player player, long clueId) {
        ItemStack evidence = new ItemStack(Material.PAPER);
        evidence.editMeta(meta -> {
            meta.displayName(messages.component(player, "police.evidence-name",
                    Placeholder.unparsed("id", Long.toString(clueId))));
            meta.getPersistentDataContainer().set(evidenceKey, PersistentDataType.LONG, clueId);
        });
        return evidence;
    }

    private Long clueId(Item item) {
        return item.getItemStack().getPersistentDataContainer().get(clueKey, PersistentDataType.LONG);
    }

    private void containmentNotice(Player player) {
        long now = System.currentTimeMillis();
        long previous = containmentNotices.getOrDefault(player.getUniqueId(), 0L);
        if (now - previous >= 2_000) {
            containmentNotices.put(player.getUniqueId(), now);
            messages.send(player, "police.detained-boundary");
        }
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }

    private static boolean changedBlock(Location from, Location to) {
        return !from.getWorld().equals(to.getWorld()) || from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ();
    }
}
