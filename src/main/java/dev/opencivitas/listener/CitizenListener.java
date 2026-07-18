package dev.opencivitas.listener;

import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.economy.Money;
import dev.opencivitas.message.MessageService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.logging.Level;

public final class CitizenListener implements Listener {
    private final JavaPlugin plugin;
    private final Database database;
    private final CitizenRepository citizens;
    private final MessageService messages;
    private final long startingBalanceCents;
    private final String currencySymbol;

    public CitizenListener(
            JavaPlugin plugin,
            Database database,
            CitizenRepository citizens,
            MessageService messages,
            long startingBalanceCents,
            String currencySymbol
    ) {
        this.plugin = plugin;
        this.database = database;
        this.citizens = citizens;
        this.messages = messages;
        this.startingBalanceCents = startingBalanceCents;
        this.currencySymbol = currencySymbol;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        String clientLocale = player.locale().toString();
        long joinedAt = System.currentTimeMillis();
        database.submit(() -> {
            var registration = citizens.register(playerId, playerName, clientLocale, startingBalanceCents);
            citizens.startActivitySession(playerId, joinedAt);
            return registration;
        })
                .whenComplete((registration, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "Could not register citizen " + playerName, error);
                        messages.send(player, "error.database");
                        return;
                    }
                    if (!player.isOnline()) {
                        return;
                    }
                    messages.setPreference(playerId, registration.preferredLocale());
                    if (registration.created()) {
                        messages.send(player, "plugin.welcome",
                                Placeholder.unparsed("amount",
                                        Money.format(registration.balanceCents(), currencySymbol)));
                    }
                }));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        long quitAt = System.currentTimeMillis();
        messages.clear(playerId);
        database.submit(() -> {
            citizens.endActivitySession(playerId, quitAt);
            citizens.updateLastSeen(playerId);
            return null;
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Could not update citizen last-seen time", error);
            return null;
        });
    }
}
