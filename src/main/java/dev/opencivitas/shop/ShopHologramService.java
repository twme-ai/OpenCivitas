package dev.opencivitas.shop;

import dev.opencivitas.database.Database;
import dev.opencivitas.economy.Money;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class ShopHologramService implements Listener {
    private final JavaPlugin plugin;
    private final Database database;
    private final ShopRepository shops;
    private final String currencySymbol;
    private final NamespacedKey shopIdKey;
    private final Map<Long, ChestShop> activeShops = new HashMap<>();
    private final Map<Long, TextDisplay> displays = new HashMap<>();
    private final Map<UUID, Boolean> visibility = new HashMap<>();
    private final Set<UUID> loadedPreferences = new HashSet<>();

    public ShopHologramService(
            JavaPlugin plugin,
            Database database,
            ShopRepository shops,
            String currencySymbol
    ) {
        this.plugin = plugin;
        this.database = database;
        this.shops = shops;
        this.currencySymbol = currencySymbol;
        shopIdKey = new NamespacedKey(plugin, "shop-hologram-id");
    }

    public void start(List<ChestShop> active) {
        for (World world : Bukkit.getWorlds()) {
            for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                if (display.getPersistentDataContainer().has(shopIdKey, PersistentDataType.LONG)) {
                    display.remove();
                }
            }
        }
        activeShops.clear();
        for (ChestShop shop : active) {
            activeShops.put(shop.id(), shop);
            spawnOrUpdate(shop);
        }
        for (Player player : Bukkit.getOnlinePlayers()) loadPreference(player);
    }

    public void upsert(ChestShop shop) {
        activeShops.put(shop.id(), shop);
        spawnOrUpdate(shop);
    }

    public void remove(long shopId) {
        activeShops.remove(shopId);
        TextDisplay display = displays.remove(shopId);
        if (display != null && display.isValid()) display.remove();
    }

    public void setVisible(Player player, boolean visible) {
        visibility.put(player.getUniqueId(), visible);
        loadedPreferences.add(player.getUniqueId());
        for (TextDisplay display : displays.values()) applyVisibility(player, display);
    }

    public void stop() {
        for (TextDisplay display : displays.values()) {
            if (display.isValid()) display.remove();
        }
        displays.clear();
        activeShops.clear();
        visibility.clear();
        loadedPreferences.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        loadPreference(event.getPlayer());
    }

    private void loadPreference(Player player) {
        UUID playerId = player.getUniqueId();
        loadedPreferences.remove(playerId);
        for (TextDisplay display : displays.values()) player.hideEntity(plugin, display);
        database.submit(() -> shops.hologramsVisible(playerId)).whenComplete((visible, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player current = Bukkit.getPlayer(playerId);
                    if (current == null) return;
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Could not load shop hologram preference", error);
                        return;
                    }
                    setVisible(current, visible);
                }));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        visibility.remove(event.getPlayer().getUniqueId());
        loadedPreferences.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (ChestShop shop : activeShops.values()) {
            if (shop.worldName().equals(event.getWorld().getName())
                    && (shop.signX() >> 4) == event.getChunk().getX()
                    && (shop.signZ() >> 4) == event.getChunk().getZ()) {
                spawnOrUpdate(shop);
            }
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        displays.entrySet().removeIf(entry -> {
            TextDisplay display = entry.getValue();
            if (display.getWorld().equals(event.getWorld())
                    && display.getLocation().getBlockX() >> 4 == event.getChunk().getX()
                    && display.getLocation().getBlockZ() >> 4 == event.getChunk().getZ()) {
                if (display.isValid()) display.remove();
                return true;
            }
            return false;
        });
    }

    private void spawnOrUpdate(ChestShop shop) {
        World world = Bukkit.getWorld(shop.worldName());
        if (world == null || !world.isChunkLoaded(shop.signX() >> 4, shop.signZ() >> 4)) return;
        Location location = new Location(
                world, shop.signX() + 0.5, shop.signY() + 1.35, shop.signZ() + 0.5);
        TextDisplay display = displays.get(shop.id());
        if (display == null || !display.isValid()) {
            display = world.spawn(location, TextDisplay.class, created -> {
                created.setPersistent(false);
                created.setGravity(false);
                created.setInvulnerable(true);
                created.setSilent(true);
                created.setBillboard(Display.Billboard.CENTER);
                created.setViewRange(24.0f);
                created.setShadowed(true);
                created.setSeeThrough(false);
                created.setDefaultBackground(true);
                created.setAlignment(TextDisplay.TextAlignment.CENTER);
                created.setLineWidth(220);
                created.getPersistentDataContainer().set(
                        shopIdKey, PersistentDataType.LONG, shop.id());
            });
            displays.put(shop.id(), display);
        } else if (!display.getLocation().equals(location)) {
            display.teleport(location);
        }
        display.text(text(shop));
        for (Player player : Bukkit.getOnlinePlayers()) applyVisibility(player, display);
    }

    private void applyVisibility(Player player, TextDisplay display) {
        boolean visible = loadedPreferences.contains(player.getUniqueId())
                && visibility.getOrDefault(player.getUniqueId(), true);
        if (visible) player.showEntity(plugin, display);
        else player.hideEntity(plugin, display);
    }

    private Component text(ChestShop shop) {
        String buy = shop.buyPriceCents() == null ? "-" : Money.format(shop.buyPriceCents(), currencySymbol);
        String sell = shop.sellPriceCents() == null ? "-" : Money.format(shop.sellPriceCents(), currencySymbol);
        String item = shop.itemKey().toLowerCase(Locale.ROOT).replace('_', ' ');
        String account = shop.accountName() == null ? "Shop" : shop.accountName();
        return Component.text(account + "\n" + shop.quantity() + " " + item
                + " | B " + buy + " | S " + sell);
    }
}
