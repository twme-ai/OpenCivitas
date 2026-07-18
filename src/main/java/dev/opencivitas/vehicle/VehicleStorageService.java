package dev.opencivitas.vehicle;

import dev.opencivitas.database.Database;
import dev.opencivitas.message.MessageService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class VehicleStorageService implements Listener {
    private final JavaPlugin plugin;
    private final Database database;
    private final VehicleRepository vehicles;
    private final VehicleManager manager;
    private final VehicleRegistry registry;
    private final MessageService messages;
    private final Map<UUID, TrunkHolder> open = new HashMap<>();
    private final Set<UUID> loading = new HashSet<>();

    public VehicleStorageService(
            JavaPlugin plugin,
            Database database,
            VehicleRepository vehicles,
            VehicleManager manager,
            VehicleRegistry registry,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.vehicles = vehicles;
        this.manager = manager;
        this.registry = registry;
        this.messages = messages;
    }

    public void open(Player player, ActiveVehicle vehicle) {
        if (!vehicle.state().ownerId().equals(player.getUniqueId())) {
            messages.send(player, "vehicle.not-owner");
            return;
        }
        if (open.containsKey(vehicle.state().id()) || !loading.add(vehicle.state().id())) {
            messages.send(player, "vehicle.storage-in-use");
            return;
        }
        UUID id = vehicle.state().id();
        database.submit(() -> vehicles.storage(id)).whenComplete((stored, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                loading.remove(id);
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Could not load vehicle storage", error);
                    messages.send(player, "error.database");
                    return;
                }
                ActiveVehicle current = manager.vehicle(vehicle.seat()).orElse(null);
                if (current == null || !current.state().ownerId().equals(player.getUniqueId())) {
                    messages.send(player, "vehicle.not-found");
                    return;
                }
                TrunkHolder holder = new TrunkHolder(id, current.definition().storageSlots(),
                        messages.component(player, "vehicle.storage-title",
                                Placeholder.unparsed("vehicle", registry.name(
                                        current.definition(), messages.locale(player)))));
                restore(holder.inventory, stored);
                open.put(id, holder);
                player.openInventory(holder.inventory);
            });
        });
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof TrunkHolder holder)) return;
        open.remove(holder.vehicleId, holder);
        byte[] contents = serialize(event.getInventory());
        database.submit(() -> {
            vehicles.saveStorage(holder.vehicleId, contents, System.currentTimeMillis());
            return null;
        }).exceptionally(error -> {
            if (plugin.isEnabled()) plugin.getLogger().log(Level.SEVERE, "Could not save vehicle storage", error);
            return null;
        });
    }

    public void stop() {
        List<StorageSnapshot> snapshots = open.values().stream()
                .map(holder -> new StorageSnapshot(holder.vehicleId, serialize(holder.inventory))).toList();
        open.clear();
        loading.clear();
        if (snapshots.isEmpty()) return;
        try {
            database.submit(() -> {
                long now = System.currentTimeMillis();
                for (StorageSnapshot snapshot : snapshots) {
                    vehicles.saveStorage(snapshot.vehicleId, snapshot.contents, now);
                }
                return null;
            }).join();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not save final vehicle storage", exception);
        }
    }

    private static byte[] serialize(Inventory inventory) {
        return ItemStack.serializeItemsAsBytes(inventory.getContents());
    }

    private static void restore(Inventory inventory, byte[] stored) {
        if (stored == null || stored.length == 0) return;
        try {
            ItemStack[] contents = ItemStack.deserializeItemsFromBytes(stored);
            for (int slot = 0; slot < Math.min(contents.length, inventory.getSize()); slot++) {
                inventory.setItem(slot, contents[slot]);
            }
        } catch (IllegalArgumentException ignored) {
            // A corrupt trunk remains empty rather than loading untrusted item data.
        }
    }

    private record StorageSnapshot(UUID vehicleId, byte[] contents) { }

    private static final class TrunkHolder implements InventoryHolder {
        private final UUID vehicleId;
        private final Inventory inventory;

        private TrunkHolder(UUID vehicleId, int size, net.kyori.adventure.text.Component title) {
            this.vehicleId = vehicleId;
            inventory = Bukkit.createInventory(this, size, title);
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
