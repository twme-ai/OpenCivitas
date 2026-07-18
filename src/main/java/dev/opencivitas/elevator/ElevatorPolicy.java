package dev.opencivitas.elevator;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class ElevatorPolicy {
    private final boolean enabled;
    private final Material floorMaterial;
    private final int maximumDistance;
    private final long cooldownNanos;
    private final Set<String> enabledWorlds;

    public ElevatorPolicy(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "elevators.yml");
        if (!file.exists()) plugin.saveResource("elevators.yml", false);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);

        enabled = configuration.getBoolean("enabled", true);
        String materialName = configuration.getString("floor-material", "IRON_BLOCK");
        floorMaterial = Material.matchMaterial(materialName == null ? "" : materialName.trim());
        if (floorMaterial == null || !floorMaterial.isBlock() || !floorMaterial.isSolid()) {
            throw new IllegalArgumentException("floor-material must be a solid block material");
        }
        maximumDistance = bounded(configuration.getInt("maximum-distance", 384),
                1, 4_096, "maximum-distance");
        int cooldownTicks = bounded(configuration.getInt("cooldown-ticks", 10),
                0, 200, "cooldown-ticks");
        cooldownNanos = cooldownTicks * 50_000_000L;

        Set<String> worlds = new LinkedHashSet<>();
        for (String world : configuration.getStringList("enabled-worlds")) {
            String normalized = world.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) worlds.add(normalized);
        }
        enabledWorlds = Set.copyOf(worlds);
    }

    public boolean enabledIn(World world) {
        return enabled && (enabledWorlds.isEmpty()
                || enabledWorlds.contains(world.getName().toLowerCase(Locale.ROOT)));
    }

    public Material floorMaterial() {
        return floorMaterial;
    }

    public int maximumDistance() {
        return maximumDistance;
    }

    public long cooldownNanos() {
        return cooldownNanos;
    }

    private static int bounded(int value, int minimum, int maximum, String path) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(path + " must be from " + minimum + " to " + maximum);
        }
        return value;
    }
}
