package dev.opencivitas.election;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class ElectionRegistry {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");

    private final Map<String, ElectionDefinition> definitions;

    public ElectionRegistry(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "elections.yml");
        if (!file.exists()) {
            plugin.saveResource("elections.yml", false);
        }
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection offices = configuration.getConfigurationSection("offices");
        if (offices == null) {
            throw new IllegalArgumentException("elections.yml does not contain an offices section");
        }
        Map<String, ElectionDefinition> loaded = new LinkedHashMap<>();
        for (String rawId : offices.getKeys(false)) {
            String id = rawId.toLowerCase(Locale.ROOT);
            validateId(id, "office");
            String path = rawId + ".";
            ElectionMethod method;
            try {
                method = ElectionMethod.valueOf(offices.getString(path + "method", "IRV")
                        .toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid election method for " + id, exception);
            }
            if (method == ElectionMethod.REFERENDUM) {
                throw new IllegalArgumentException("Office " + id + " cannot use the referendum method");
            }
            int seats = positive(offices.getInt(path + "seats", 1), path + "seats");
            if (method == ElectionMethod.IRV && seats != 1) {
                throw new IllegalArgumentException("IRV office " + id + " must have exactly one seat");
            }
            int termDays = positive(offices.getInt(path + "term-days", 120), path + "term-days");
            boolean runningMate = offices.getBoolean(path + "running-mate.required", false);
            String mateOffice = offices.getString(path + "running-mate.office");
            if (runningMate && (mateOffice == null || !ID.matcher(mateOffice).matches())) {
                throw new IllegalArgumentException("Office " + id + " requires a valid running-mate office");
            }
            loaded.put(id, new ElectionDefinition(
                    id,
                    method,
                    seats,
                    termDays,
                    days(offices.getInt(path + "eligibility.citizenship-days", 0), path),
                    hours(offices.getInt(path + "eligibility.total-hours", 0), path),
                    hours(offices.getInt(path + "eligibility.recent-hours", 0), path),
                    days(positive(offices.getInt(path + "eligibility.recent-window-days", 30),
                            path + "eligibility.recent-window-days"), path),
                    runningMate,
                    mateOffice,
                    offices.getBoolean(path + "eligibility.disallow-immediate-reelection", false),
                    days(offices.getInt(path + "running-mate.eligibility.citizenship-days", 0), path),
                    hours(offices.getInt(path + "running-mate.eligibility.total-hours", 0), path),
                    hours(offices.getInt(path + "running-mate.eligibility.recent-hours", 0), path),
                    offices.getBoolean(path + "running-mate.eligibility.disallow-most-recent-president", false)
            ));
        }
        definitions = Map.copyOf(loaded);
    }

    public Optional<ElectionDefinition> find(String id) {
        return Optional.ofNullable(definitions.get(id.toLowerCase(Locale.ROOT)));
    }

    public Collection<ElectionDefinition> all() {
        return definitions.values().stream().sorted(Comparator.comparing(ElectionDefinition::id)).toList();
    }

    private static int positive(int value, String path) {
        if (value < 1) {
            throw new IllegalArgumentException(path + " must be at least 1");
        }
        return value;
    }

    private static Duration days(int value, String path) {
        if (value < 0) throw new IllegalArgumentException(path + " day values cannot be negative");
        return Duration.ofDays(value);
    }

    private static Duration hours(int value, String path) {
        if (value < 0) throw new IllegalArgumentException(path + " hour values cannot be negative");
        return Duration.ofHours(value);
    }

    private static void validateId(String value, String label) {
        if (!ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + label + " id: " + value);
        }
    }
}
