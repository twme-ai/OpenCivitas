package dev.opencivitas.police;

import dev.opencivitas.economy.Money;
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

public final class PolicePolicy {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");

    private final Map<String, Offense> offenses;
    private final Duration selfDefenseWindow;
    private final Duration reportWindow;
    private final double arrestDistance;
    private final int warrantHoldMinutes;
    private final int maximumCombinedJailMinutes;

    public PolicePolicy(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "law-enforcement.yml");
        if (!file.exists()) plugin.saveResource("law-enforcement.yml", false);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);

        selfDefenseWindow = Duration.ofSeconds(bounded(
                configuration.getLong("self-defense-window-seconds", 300), 10, 3_600,
                "self-defense-window-seconds"));
        reportWindow = Duration.ofSeconds(bounded(
                configuration.getLong("report-window-seconds", 300), 30, 86_400,
                "report-window-seconds"));
        arrestDistance = boundedDouble(
                configuration.getDouble("arrest-distance", 5), 1, 32, "arrest-distance");
        warrantHoldMinutes = (int) bounded(
                configuration.getLong("warrant-hold-minutes", 10), 1, 120,
                "warrant-hold-minutes");
        maximumCombinedJailMinutes = (int) bounded(
                configuration.getLong("maximum-combined-jail-minutes", 120), 1, 1_440,
                "maximum-combined-jail-minutes");

        ConfigurationSection section = configuration.getConfigurationSection("offenses");
        if (section == null) throw new IllegalArgumentException(
                "law-enforcement.yml does not contain an offenses section");
        Map<String, Offense> loaded = new LinkedHashMap<>();
        for (String rawId : section.getKeys(false)) {
            String id = rawId.toLowerCase(Locale.ROOT);
            if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid offense id: " + rawId);
            long fine;
            try {
                fine = Money.parseCents(section.getString(rawId + ".fine", "0"));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid fine for offense " + id, exception);
            }
            if (fine > 100_000_000_000L) throw new IllegalArgumentException(
                    "Fine for offense " + id + " may not exceed $1,000,000,000");
            int jail = (int) bounded(section.getLong(rawId + ".jail-minutes", 0),
                    0, maximumCombinedJailMinutes, "offenses." + rawId + ".jail-minutes");
            if (fine == 0 && jail == 0) throw new IllegalArgumentException(
                    "Offense " + id + " must define a fine or jail sentence");
            loaded.put(id, new Offense(id, fine, jail));
        }
        if (loaded.isEmpty()) throw new IllegalArgumentException("At least one offense must be configured");
        offenses = Map.copyOf(loaded);
    }

    public Optional<Offense> offense(String id) {
        return Optional.ofNullable(offenses.get(id.toLowerCase(Locale.ROOT)));
    }

    public Collection<Offense> offenses() {
        return offenses.values().stream().sorted(Comparator.comparing(Offense::id)).toList();
    }

    public Duration selfDefenseWindow() {
        return selfDefenseWindow;
    }

    public Duration reportWindow() {
        return reportWindow;
    }

    public double arrestDistance() {
        return arrestDistance;
    }

    public int warrantHoldMinutes() {
        return warrantHoldMinutes;
    }

    public int maximumCombinedJailMinutes() {
        return maximumCombinedJailMinutes;
    }

    private static long bounded(long value, long minimum, long maximum, String path) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(
                path + " must be from " + minimum + " to " + maximum);
        return value;
    }

    private static double boundedDouble(double value, double minimum, double maximum, String path) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(path + " must be from " + minimum + " to " + maximum);
        }
        return value;
    }
}
