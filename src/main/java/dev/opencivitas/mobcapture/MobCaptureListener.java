package dev.opencivitas.mobcapture;

import dev.opencivitas.database.Database;
import dev.opencivitas.economy.Money;
import dev.opencivitas.message.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Egg;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerEggThrowEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public final class MobCaptureListener implements Listener {
    private final JavaPlugin plugin;
    private final Database database;
    private final MobCapturePolicy policy;
    private final MobCaptureRepository repository;
    private final MessageService messages;
    private final String currencySymbol;
    private final NamespacedKey captureAttemptKey;
    private final Set<UUID> pendingTargets = new HashSet<>();
    private final Map<UUID, Integer> pendingByPlayer = new HashMap<>();

    public MobCaptureListener(
            JavaPlugin plugin,
            Database database,
            MobCapturePolicy policy,
            MobCaptureRepository repository,
            MessageService messages,
            String currencySymbol
    ) {
        this.plugin = plugin;
        this.database = database;
        this.policy = policy;
        this.repository = repository;
        this.messages = messages;
        this.currencySymbol = currencySymbol;
        captureAttemptKey = new NamespacedKey(plugin, "mob-capture-attempt");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEggHit(ProjectileHitEvent event) {
        if (!policy.enabled()
                || !(event.getEntity() instanceof Egg egg)
                || !(egg.getShooter() instanceof Player player)
                || !(event.getHitEntity() instanceof LivingEntity target)) {
            return;
        }
        Set<String> eligibleJobs = policy.jobs(target.getType());
        if (eligibleJobs.isEmpty()
                || egg.getPersistentDataContainer().has(captureAttemptKey, PersistentDataType.BYTE)) return;
        egg.getPersistentDataContainer().set(captureAttemptKey, PersistentDataType.BYTE, (byte) 1);

        MobCaptureRestriction restriction = restriction(target);
        if (restriction != MobCaptureRestriction.ALLOWED) {
            restricted(player, restriction);
            return;
        }
        int pending = pendingByPlayer.getOrDefault(player.getUniqueId(), 0);
        if (pending >= policy.maximumPendingPerPlayer() || !pendingTargets.add(target.getUniqueId())) {
            messages.send(player, "mob-capture.busy");
            return;
        }
        pendingByPlayer.put(player.getUniqueId(), pending + 1);

        Location hit = target.getLocation().clone();
        boolean successfulRoll = policy.chance().succeeds(
                ThreadLocalRandom.current().nextInt(policy.chance().denominator()));
        database.submit(() -> repository.authorize(
                        player.getUniqueId(), player.getName(), target.getUniqueId(), target.getType().name(),
                        eligibleJobs, hit.getWorld().getName(), hit.getX(), hit.getY(), hit.getZ(),
                        policy.feeCents(), successfulRoll, Instant.now().toEpochMilli()))
                .whenComplete((authorization, error) -> {
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> finish(
                            player, target, hit, authorization, error));
                });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEggThrow(PlayerEggThrowEvent event) {
        if (event.getEgg().getPersistentDataContainer().has(captureAttemptKey, PersistentDataType.BYTE)) {
            event.setHatching(false);
            event.setNumHatches((byte) 0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnEggUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getItem() == null) return;
        restrictSpawnEgg(event.getPlayer(), event.getItem(), () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnEggUseEntity(PlayerInteractEntityEvent event) {
        ItemStack item = event.getHand() == EquipmentSlot.HAND
                ? event.getPlayer().getInventory().getItemInMainHand()
                : event.getPlayer().getInventory().getItemInOffHand();
        restrictSpawnEgg(event.getPlayer(), item, () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispenser(BlockDispenseEvent event) {
        if (policy.enabled() && policy.spawnEggRestricted(event.getBlock().getWorld())
                && isSpawnEgg(event.getItem().getType())) {
            event.setCancelled(true);
        }
    }

    private void finish(
            Player player,
            LivingEntity target,
            Location hit,
            MobCaptureAuthorization authorization,
            Throwable error
    ) {
        release(player.getUniqueId(), target.getUniqueId());
        if (error != null) {
            plugin.getLogger().log(Level.SEVERE, "Could not authorize a mob capture", error);
            if (player.isOnline()) messages.send(player, "error.database");
            return;
        }
        switch (authorization.result()) {
            case NOT_QUALIFIED -> messages.send(player, "mob-capture.not-qualified");
            case CHANCE_FAILED -> messages.send(player, "mob-capture.chance-failed");
            case INSUFFICIENT_FUNDS -> messages.send(player, "mob-capture.insufficient-funds",
                    Placeholder.unparsed("fee", Money.format(policy.feeCents(), currencySymbol)));
            case ACCOUNT_NOT_FOUND -> messages.send(player, "mob-capture.account-not-found");
            case DUPLICATE_TARGET -> messages.send(player, "mob-capture.busy");
            case SUCCESS -> capture(player, target, hit, authorization);
        }
    }

    private void capture(
            Player player,
            LivingEntity target,
            Location hit,
            MobCaptureAuthorization authorization
    ) {
        if (!target.isValid() || target.isDead() || restriction(target) != MobCaptureRestriction.ALLOWED) {
            refund(authorization.auditId(), "target-unavailable");
            if (player.isOnline()) messages.send(player, "mob-capture.target-unavailable");
            return;
        }
        Material material = policy.egg(target.getType());
        if (material == null) {
            refund(authorization.auditId(), "spawn-egg-unavailable");
            if (player.isOnline()) messages.send(player, "mob-capture.target-unavailable");
            return;
        }

        UUID targetId = target.getUniqueId();
        String entityType = target.getType().name();
        Location location = target.getLocation().clone();
        target.remove();
        ItemStack spawnEgg = new ItemStack(material);
        if (player.isOnline()) {
            player.getInventory().addItem(spawnEgg).values()
                    .forEach(leftover -> location.getWorld().dropItemNaturally(location, leftover));
        } else {
            location.getWorld().dropItemNaturally(location, spawnEgg);
        }
        location.getWorld().spawnParticle(Particle.POOF, location.clone().add(0, 0.5, 0), 18, 0.35, 0.5, 0.35, 0.02);
        location.getWorld().playSound(location, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.35f);
        database.submit(() -> repository.complete(authorization.auditId(), Instant.now().toEpochMilli()))
                .whenComplete((completed, error) -> {
                    if (error != null || !Boolean.TRUE.equals(completed)) {
                        plugin.getLogger().log(Level.SEVERE,
                                "Could not finalize mob capture audit " + authorization.auditId(), error);
                    }
                });
        if (player.isOnline()) {
            messages.send(player, "mob-capture.success",
                    Placeholder.component("entity", Component.translatable(target.getType().translationKey())),
                    Placeholder.unparsed("fee", Money.format(policy.feeCents(), currencySymbol)),
                    Placeholder.unparsed("balance", Money.format(authorization.balanceCents(), currencySymbol)));
        }
        plugin.getLogger().info("Mob capture audit=" + authorization.auditId()
                + " actor=" + player.getName() + "(" + player.getUniqueId() + ")"
                + " target=" + targetId + " type=" + entityType
                + " world=" + hit.getWorld().getName()
                + " x=" + hit.getBlockX() + " y=" + hit.getBlockY() + " z=" + hit.getBlockZ());
    }

    private void refund(long auditId, String reason) {
        database.submit(() -> repository.refund(auditId, reason, Instant.now().toEpochMilli()))
                .whenComplete((refunded, error) -> {
                    if (error != null || !Boolean.TRUE.equals(refunded)) {
                        plugin.getLogger().log(Level.SEVERE,
                                "Could not refund mob capture audit " + auditId, error);
                    }
                });
    }

    private void release(UUID playerId, UUID targetId) {
        pendingTargets.remove(targetId);
        int remaining = pendingByPlayer.getOrDefault(playerId, 1) - 1;
        if (remaining <= 0) pendingByPlayer.remove(playerId);
        else pendingByPlayer.put(playerId, remaining);
    }

    private void restrictSpawnEgg(Player player, ItemStack item, Runnable cancel) {
        if (!policy.enabled() || !policy.spawnEggRestricted(player.getWorld())
                || !isSpawnEgg(item.getType())
                || player.hasPermission("opencivitas.mobcapture.spawn-bypass")) {
            return;
        }
        cancel.run();
        messages.send(player, "mob-capture.spawn-restricted");
    }

    private void restricted(Player player, MobCaptureRestriction restriction) {
        messages.send(player, "mob-capture.restriction."
                + restriction.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static MobCaptureRestriction restriction(LivingEntity target) {
        return MobCaptureRestriction.evaluate(
                target.customName() != null,
                target instanceof Ageable ageable && !ageable.isAdult(),
                target instanceof Tameable tameable && tameable.isTamed(),
                target instanceof Sheep sheep && sheep.isSheared());
    }

    private static boolean isSpawnEgg(Material material) {
        return material.name().endsWith("_SPAWN_EGG");
    }
}
