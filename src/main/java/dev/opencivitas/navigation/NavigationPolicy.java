package dev.opencivitas.navigation;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class NavigationPolicy {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");

    private final int maximumHomes;
    private final String mapUrl;
    private final long gpsUpdateTicks;
    private final Map<String, String> warpAliases;

    public NavigationPolicy(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "navigation.yml");
        if (!file.exists()) plugin.saveResource("navigation.yml", false);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        maximumHomes = bounded(configuration.getInt("maximum-homes", 3), 1, 100, "maximum-homes");
        mapUrl = validUrl(configuration.getString("map-url", "").trim());
        gpsUpdateTicks = bounded(configuration.getLong("gps-update-ticks", 20), 10, 200, "gps-update-ticks");
        ConfigurationSection section = configuration.getConfigurationSection("warp-aliases");
        if (section == null) throw new IllegalArgumentException("navigation.yml does not contain warp-aliases");
        Map<String, String> aliases = new LinkedHashMap<>();
        for (String rawAlias : section.getKeys(false)) {
            String alias = id(rawAlias, "warp alias");
            String warp = id(section.getString(rawAlias, ""), "warp id");
            aliases.put(alias, warp);
        }
        warpAliases = Map.copyOf(aliases);
    }

    public int maximumHomes() {
        return maximumHomes;
    }

    public String mapUrl() {
        return mapUrl;
    }

    public long gpsUpdateTicks() {
        return gpsUpdateTicks;
    }

    public Optional<String> warpForCommand(String command) {
        return Optional.ofNullable(warpAliases.get(command.toLowerCase(Locale.ROOT)));
    }

    public Map<String, String> warpAliases() {
        return warpAliases;
    }

    public static boolean validId(String value) {
        return value != null && ID.matcher(value.toLowerCase(Locale.ROOT)).matches();
    }

    private static String id(String value, String type) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (!ID.matcher(normalized).matches()) throw new IllegalArgumentException("Invalid " + type + ": " + value);
        return normalized;
    }

    private static int bounded(int value, int minimum, int maximum, String path) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(
                path + " must be from " + minimum + " to " + maximum);
        return value;
    }

    private static long bounded(long value, long minimum, long maximum, String path) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(
                path + " must be from " + minimum + " to " + maximum);
        return value;
    }

    private static String validUrl(String value) {
        if (value.isEmpty()) return value;
        try {
            URI uri = URI.create(value);
            if (!(uri.getScheme().equalsIgnoreCase("https") || uri.getScheme().equalsIgnoreCase("http"))
                    || uri.getHost() == null) throw new IllegalArgumentException();
            return uri.toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("map-url must be an absolute HTTP or HTTPS URL", exception);
        }
    }
}
