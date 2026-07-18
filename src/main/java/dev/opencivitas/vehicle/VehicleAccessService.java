package dev.opencivitas.vehicle;

import dev.opencivitas.database.Database;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class VehicleAccessService {
    private final JavaPlugin plugin;
    private final Database database;
    private final VehicleRepository vehicles;
    private final Map<UUID, VehicleAccess> access = new ConcurrentHashMap<>();

    public VehicleAccessService(JavaPlugin plugin, Database database, VehicleRepository vehicles) {
        this.plugin = plugin;
        this.database = database;
        this.vehicles = vehicles;
    }

    public boolean isMechanic(UUID playerId) {
        return access.getOrDefault(playerId, new VehicleAccess(false, java.util.Set.of())).mechanic();
    }

    public boolean hasLicense(UUID playerId, String licenseId) {
        return access.getOrDefault(playerId, new VehicleAccess(false, java.util.Set.of()))
                .hasLicense(licenseId);
    }

    public void refresh(UUID playerId) {
        refresh(playerId, ignored -> { });
    }

    public void refresh(UUID playerId, Consumer<VehicleAccess> callback) {
        database.submit(() -> vehicles.access(playerId, System.currentTimeMillis()))
                .whenComplete((loaded, error) -> {
                    if (!plugin.isEnabled()) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (error != null) {
                            plugin.getLogger().log(Level.WARNING, "Could not refresh vehicle access", error);
                            return;
                        }
                        access.put(playerId, loaded);
                        callback.accept(loaded);
                    });
                });
    }

    public void forget(UUID playerId) {
        access.remove(playerId);
    }
}
