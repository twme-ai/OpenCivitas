package dev.opencivitas.mobcapture;

import dev.opencivitas.economy.Money;
import dev.opencivitas.job.JobRegistry;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MobCapturePolicy {
    private final boolean enabled;
    private final long feeCents;
    private final MobCaptureChance chance;
    private final int maximumPendingPerPlayer;
    private final Map<EntityType, Set<String>> jobsByEntity;
    private final Map<EntityType, Material> eggsByEntity;
    private final Set<String> spawnRestrictedWorlds;

    public MobCapturePolicy(JavaPlugin plugin, JobRegistry jobs) {
        File file = new File(plugin.getDataFolder(), "mob-capture.yml");
        if (!file.exists()) plugin.saveResource("mob-capture.yml", false);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        enabled = configuration.getBoolean("enabled", true);
        feeCents = Money.parsePositiveCents(configuration.getString("capture-fee", "50.00"));
        chance = new MobCaptureChance(
                configuration.getInt("chance.numerator", 1),
                configuration.getInt("chance.denominator", 3));
        maximumPendingPerPlayer = bounded(
                configuration.getInt("maximum-pending-per-player", 4), 1, 64);

        ConfigurationSection section = configuration.getConfigurationSection("jobs");
        if (section == null || section.getKeys(false).isEmpty()) {
            throw new IllegalArgumentException("mob-capture.yml must define at least one job catalog");
        }
        Map<EntityType, Set<String>> loadedJobs = new EnumMap<>(EntityType.class);
        Map<EntityType, Material> loadedEggs = new EnumMap<>(EntityType.class);
        for (String rawJob : section.getKeys(false)) {
            String job = rawJob.toLowerCase(Locale.ROOT);
            if (jobs.find(job).isEmpty()) {
                throw new IllegalArgumentException("Unknown mob-capture job: " + rawJob);
            }
            List<String> values = section.getStringList(rawJob);
            if (values.isEmpty()) {
                throw new IllegalArgumentException("Mob-capture job has no entities: " + rawJob);
            }
            for (String rawType : values) {
                EntityType entityType;
                try {
                    entityType = EntityType.valueOf(rawType.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("Unknown mob-capture entity: " + rawType, exception);
                }
                if (!entityType.isAlive()) {
                    throw new IllegalArgumentException("Mob-capture entity is not living: " + rawType);
                }
                Material egg = Material.matchMaterial(entityType.name() + "_SPAWN_EGG");
                if (egg == null) {
                    throw new IllegalArgumentException("Entity has no spawn egg: " + entityType.name());
                }
                loadedJobs.computeIfAbsent(entityType, ignored -> new HashSet<>()).add(job);
                loadedEggs.put(entityType, egg);
            }
        }
        Map<EntityType, Set<String>> immutableJobs = new HashMap<>();
        loadedJobs.forEach((type, values) -> immutableJobs.put(type, Set.copyOf(values)));
        jobsByEntity = Map.copyOf(immutableJobs);
        eggsByEntity = Map.copyOf(loadedEggs);
        spawnRestrictedWorlds = configuration.getStringList("spawn-egg-restricted-worlds").stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean enabled() {
        return enabled;
    }

    public long feeCents() {
        return feeCents;
    }

    public MobCaptureChance chance() {
        return chance;
    }

    public int maximumPendingPerPlayer() {
        return maximumPendingPerPlayer;
    }

    public Set<String> jobs(EntityType type) {
        return jobsByEntity.getOrDefault(type, Set.of());
    }

    public Material egg(EntityType type) {
        return eggsByEntity.get(type);
    }

    public boolean spawnEggRestricted(World world) {
        return spawnRestrictedWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    public int entityCount() {
        return jobsByEntity.size();
    }

    private static int bounded(int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    "maximum-pending-per-player must be from " + minimum + " to " + maximum);
        }
        return value;
    }
}
