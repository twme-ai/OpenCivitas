package dev.opencivitas.family;

import dev.opencivitas.message.MessageService;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

public final class FamilyListener implements Listener {
    private final FamilyRegistry families;
    private final MessageService messages;

    public FamilyListener(FamilyRegistry families, MessageService messages) {
        this.families = families;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPartnerDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attacker(event);
        if (attacker == null || !families.blocksPvp(attacker.getUniqueId(), victim.getUniqueId())) return;
        event.setCancelled(true);
        messages.send(attacker, "family.partner-pvp-blocked");
    }

    private static Player attacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        if (event.getDamager() instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) return player;
        return null;
    }
}
