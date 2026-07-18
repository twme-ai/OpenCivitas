package dev.opencivitas.job;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class JobRegistry {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");

    private final Map<String, JobDefinition> jobs;
    private final int tradeLimit;
    private final int professionLimit;

    public JobRegistry(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "jobs.yml");
        if (!file.exists()) {
            plugin.saveResource("jobs.yml", false);
        }
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        tradeLimit = positiveLimit(configuration.getInt("limits.trade", 2), "limits.trade");
        professionLimit = positiveLimit(configuration.getInt("limits.profession", 1), "limits.profession");

        ConfigurationSection section = configuration.getConfigurationSection("jobs");
        if (section == null) {
            throw new IllegalArgumentException("jobs.yml does not contain a jobs section");
        }
        Map<String, JobDefinition> loaded = new LinkedHashMap<>();
        for (String rawId : section.getKeys(false)) {
            String id = rawId.toLowerCase(Locale.ROOT);
            if (!ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Invalid job id: " + rawId);
            }
            String categoryName = section.getString(rawId + ".category", "");
            JobCategory category;
            try {
                category = JobCategory.valueOf(categoryName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid category for job " + id, exception);
            }
            String qualification = section.getString(rawId + ".qualification", id)
                    .toLowerCase(Locale.ROOT);
            boolean selfJoin = section.getBoolean(rawId + ".self-join", category != JobCategory.GOVERNMENT);
            loaded.put(id, new JobDefinition(id, category, qualification, selfJoin));
        }
        jobs = Map.copyOf(loaded);
    }

    public Optional<JobDefinition> find(String id) {
        return Optional.ofNullable(jobs.get(id.toLowerCase(Locale.ROOT)));
    }

    public Collection<JobDefinition> all() {
        return jobs.values().stream()
                .sorted(java.util.Comparator.comparing(JobDefinition::category).thenComparing(JobDefinition::id))
                .toList();
    }

    public int limit(JobCategory category) {
        return switch (category) {
            case TRADE -> tradeLimit;
            case PROFESSION -> professionLimit;
            case GOVERNMENT -> Integer.MAX_VALUE;
        };
    }

    private static int positiveLimit(int value, String path) {
        if (value < 1) {
            throw new IllegalArgumentException(path + " must be at least 1");
        }
        return value;
    }
}
