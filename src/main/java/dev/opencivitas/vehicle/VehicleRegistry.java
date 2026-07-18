package dev.opencivitas.vehicle;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class VehicleRegistry {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");

    private final Map<String, VehicleDefinition> definitions;
    private final int maximumOwned;
    private final long saveIntervalTicks;

    public VehicleRegistry(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "vehicles.yml");
        if (!file.exists()) plugin.saveResource("vehicles.yml", false);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        maximumOwned = bounded(configuration.getInt("maximum-owned", 6), 1, 100, "maximum-owned");
        saveIntervalTicks = bounded(
                configuration.getLong("save-interval-ticks", 20), 10, 1_200, "save-interval-ticks");
        ConfigurationSection section = configuration.getConfigurationSection("vehicles");
        if (section == null) throw new IllegalArgumentException("vehicles.yml does not contain vehicles");
        Map<String, VehicleDefinition> loaded = new LinkedHashMap<>();
        for (String rawId : section.getKeys(false)) {
            String id = validId(rawId, "vehicle");
            VehicleCategory category = category(section.getString(rawId + ".category", "GROUND"), id);
            Material item = material(section.getString(rawId + ".item", "MINECART"), id);
            Material display = material(section.getString(rawId + ".display", item.name()), id);
            String license = validId(section.getString(rawId + ".license", "driver"), "license");
            long maximumFuel = bounded(section.getLong(rawId + ".maximum-fuel", 10_000),
                    100, 10_000_000, rawId + ".maximum-fuel");
            long fuelPerBlock = bounded(section.getLong(rawId + ".fuel-per-block", 10),
                    1, maximumFuel, rawId + ".fuel-per-block");
            int maximumHealth = bounded(section.getInt(rawId + ".maximum-health", 100),
                    1, 10_000, rawId + ".maximum-health");
            int storageSlots = bounded(section.getInt(rawId + ".storage-slots", 9),
                    9, 54, rawId + ".storage-slots");
            if (storageSlots % 9 != 0) throw new IllegalArgumentException(
                    rawId + ".storage-slots must be a multiple of 9");
            double maximumSpeed = bounded(section.getDouble(rawId + ".maximum-speed", 0.45),
                    0.05, 3.0, rawId + ".maximum-speed");
            double acceleration = bounded(section.getDouble(rawId + ".acceleration", 0.04),
                    0.001, maximumSpeed, rawId + ".acceleration");
            List<String> shape = section.getStringList(rawId + ".recipe.shape");
            if (shape.size() != 3 || shape.stream().anyMatch(row -> row.length() != 3)) {
                throw new IllegalArgumentException("Vehicle " + id + " needs a three-by-three recipe shape");
            }
            ConfigurationSection ingredients = section.getConfigurationSection(rawId + ".recipe.ingredients");
            if (ingredients == null) throw new IllegalArgumentException("Vehicle " + id + " has no ingredients");
            Map<Character, String> choices = new LinkedHashMap<>();
            for (String symbol : ingredients.getKeys(false)) {
                if (symbol.length() != 1 || symbol.charAt(0) == ' ') throw new IllegalArgumentException(
                        "Invalid recipe symbol " + symbol + " for vehicle " + id);
                String choice = ingredients.getString(symbol, "").trim();
                if (choice.isEmpty()) throw new IllegalArgumentException("Empty recipe choice for vehicle " + id);
                choices.put(symbol.charAt(0), choice);
            }
            for (String row : shape) for (char symbol : row.toCharArray()) {
                if (symbol != ' ' && !choices.containsKey(symbol)) throw new IllegalArgumentException(
                        "Missing recipe symbol " + symbol + " for vehicle " + id);
            }
            loaded.put(id, new VehicleDefinition(id, names(section, rawId), category,
                    item, display, license, maximumFuel, fuelPerBlock, maximumHealth,
                    storageSlots, maximumSpeed, acceleration, shape, choices));
        }
        if (loaded.isEmpty()) throw new IllegalArgumentException("At least one vehicle must be configured");
        definitions = Map.copyOf(loaded);
    }

    public Optional<VehicleDefinition> find(String id) {
        return Optional.ofNullable(definitions.get(normalize(id)));
    }

    public Collection<VehicleDefinition> all() {
        return definitions.values().stream().sorted(Comparator.comparing(VehicleDefinition::id)).toList();
    }

    public String name(VehicleDefinition definition, String locale) {
        return definition.names().getOrDefault(locale,
                definition.names().getOrDefault("en_US", definition.id()));
    }

    public int maximumOwned() {
        return maximumOwned;
    }

    public long saveIntervalTicks() {
        return saveIntervalTicks;
    }

    private static Map<String, String> names(ConfigurationSection section, String path) {
        ConfigurationSection names = section.getConfigurationSection(path + ".name");
        if (names == null) throw new IllegalArgumentException(path + " does not contain localized names");
        Map<String, String> loaded = new LinkedHashMap<>();
        for (String locale : names.getKeys(false)) {
            String value = names.getString(locale, "").trim();
            if (!value.isEmpty()) loaded.put(locale, value);
        }
        if (!loaded.containsKey("en_US")) throw new IllegalArgumentException(path + " needs an en_US name");
        return loaded;
    }

    private static Material material(String name, String id) {
        Material material = Material.matchMaterial(name);
        if (material == null || material.isAir()) throw new IllegalArgumentException(
                "Invalid material " + name + " for vehicle " + id);
        return material;
    }

    private static VehicleCategory category(String name, String id) {
        try {
            return VehicleCategory.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid category for vehicle " + id, exception);
        }
    }

    private static String validId(String raw, String type) {
        String id = normalize(raw);
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid " + type + " id: " + raw);
        return id;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
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

    private static double bounded(double value, double minimum, double maximum, String path) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) throw new IllegalArgumentException(
                path + " must be from " + minimum + " to " + maximum);
        return value;
    }
}
