package dev.opencivitas.security;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Duration;

public final class SecurityPolicy {
    private final int maximumCameras;
    private final int maximumComputers;
    private final int maximumGroups;
    private final Duration maximumViewDuration;

    public SecurityPolicy(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "security.yml");
        if (!file.exists()) plugin.saveResource("security.yml", false);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        maximumCameras = bounded(configuration.getInt("limits.cameras-per-player", 32), 1, 500,
                "limits.cameras-per-player");
        maximumComputers = bounded(configuration.getInt("limits.computers-per-player", 16), 1, 200,
                "limits.computers-per-player");
        maximumGroups = bounded(configuration.getInt("limits.groups-per-player", 16), 1, 200,
                "limits.groups-per-player");
        maximumViewDuration = Duration.ofSeconds(bounded(
                configuration.getInt("maximum-view-seconds", 300), 10, 3_600, "maximum-view-seconds"));
    }

    public int maximumCameras() {
        return maximumCameras;
    }

    public int maximumComputers() {
        return maximumComputers;
    }

    public int maximumGroups() {
        return maximumGroups;
    }

    public Duration maximumViewDuration() {
        return maximumViewDuration;
    }

    private static int bounded(int value, int minimum, int maximum, String path) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(path + " must be from " + minimum + " to " + maximum);
        }
        return value;
    }
}
