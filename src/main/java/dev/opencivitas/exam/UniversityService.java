package dev.opencivitas.exam;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

public final class UniversityService {
    private final JavaPlugin plugin;

    public UniversityService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public Optional<Location> warp() {
        FileConfiguration configuration = plugin.getConfig();
        String worldName = configuration.getString("university.warp.world");
        if (worldName == null) {
            return Optional.empty();
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return Optional.empty();
        }
        return Optional.of(new Location(
                world,
                configuration.getDouble("university.warp.x"),
                configuration.getDouble("university.warp.y"),
                configuration.getDouble("university.warp.z"),
                (float) configuration.getDouble("university.warp.yaw"),
                (float) configuration.getDouble("university.warp.pitch")
        ));
    }

    public void setWarp(Location location) {
        FileConfiguration configuration = plugin.getConfig();
        configuration.set("university.warp.world", location.getWorld().getName());
        configuration.set("university.warp.x", location.getX());
        configuration.set("university.warp.y", location.getY());
        configuration.set("university.warp.z", location.getZ());
        configuration.set("university.warp.yaw", location.getYaw());
        configuration.set("university.warp.pitch", location.getPitch());
        plugin.saveConfig();
    }
}
