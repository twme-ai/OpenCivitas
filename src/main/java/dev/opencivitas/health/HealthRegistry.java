package dev.opencivitas.health;

import dev.opencivitas.economy.Money;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class HealthRegistry {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");

    private final Map<String, HealthConditionDefinition> conditions;
    private final Map<String, TreatmentDefinition> treatments;
    private final int normalTemperature;
    private final int minimumTemperature;
    private final int maximumTemperature;
    private final int coldThreshold;
    private final int hotThreshold;
    private final int temperatureStep;
    private final Duration callClaimTimeout;

    public HealthRegistry(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "health.yml");
        if (!file.exists()) plugin.saveResource("health.yml", false);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        normalTemperature = bounded(configuration.getInt("temperature.normal-millicelsius", 37_000),
                30_000, 45_000, "temperature.normal-millicelsius");
        minimumTemperature = bounded(configuration.getInt("temperature.minimum-millicelsius", 20_000),
                10_000, normalTemperature, "temperature.minimum-millicelsius");
        maximumTemperature = bounded(configuration.getInt("temperature.maximum-millicelsius", 55_000),
                normalTemperature, 60_000, "temperature.maximum-millicelsius");
        coldThreshold = bounded(configuration.getInt("temperature.cold-threshold-millicelsius", 30_000),
                minimumTemperature, normalTemperature - 1, "temperature.cold-threshold-millicelsius");
        hotThreshold = bounded(configuration.getInt("temperature.hot-threshold-millicelsius", 45_000),
                normalTemperature + 1, maximumTemperature, "temperature.hot-threshold-millicelsius");
        temperatureStep = bounded(configuration.getInt("temperature.update-step-millicelsius", 1_000),
                100, 5_000, "temperature.update-step-millicelsius");
        callClaimTimeout = Duration.ofSeconds(bounded(
                configuration.getInt("calls.claim-timeout-seconds", 300),
                30, 3_600, "calls.claim-timeout-seconds"));
        treatments = loadTreatments(required(configuration, "treatments"));
        conditions = loadConditions(required(configuration, "conditions"), treatments);
    }

    public Optional<HealthConditionDefinition> condition(String id) {
        return Optional.ofNullable(conditions.get(normalize(id)));
    }

    public Optional<TreatmentDefinition> treatment(String id) {
        return Optional.ofNullable(treatments.get(normalize(id)));
    }

    public Collection<HealthConditionDefinition> conditions() {
        return conditions.values().stream().sorted(Comparator.comparing(HealthConditionDefinition::id)).toList();
    }

    public Collection<TreatmentDefinition> treatments() {
        return treatments.values().stream().sorted(Comparator.comparing(TreatmentDefinition::id)).toList();
    }

    public List<HealthConditionDefinition> exposedBy(String exposure) {
        String normalized = normalize(exposure);
        return conditions.values().stream().filter(condition -> condition.exposureChances().containsKey(normalized))
                .sorted(Comparator.comparing(HealthConditionDefinition::id)).toList();
    }

    public String name(HealthConditionDefinition condition, String locale) {
        return localized(condition.names(), locale, condition.id());
    }

    public String name(TreatmentDefinition treatment, String locale) {
        return localized(treatment.names(), locale, treatment.id());
    }

    public int normalTemperature() {
        return normalTemperature;
    }

    public int minimumTemperature() {
        return minimumTemperature;
    }

    public int maximumTemperature() {
        return maximumTemperature;
    }

    public int coldThreshold() {
        return coldThreshold;
    }

    public int hotThreshold() {
        return hotThreshold;
    }

    public int temperatureStep() {
        return temperatureStep;
    }

    public Duration callClaimTimeout() {
        return callClaimTimeout;
    }

    private static Map<String, TreatmentDefinition> loadTreatments(ConfigurationSection section) {
        Map<String, TreatmentDefinition> loaded = new LinkedHashMap<>();
        for (String rawId : section.getKeys(false)) {
            String id = validId(rawId, "treatment");
            Material item = material(section.getString(rawId + ".item", "POTION"), id);
            List<Material> ingredients = section.getStringList(rawId + ".ingredients").stream()
                    .map(name -> material(name, id)).toList();
            if (ingredients.isEmpty() || ingredients.size() > 9) throw new IllegalArgumentException(
                    "Treatment " + id + " must have from 1 to 9 ingredients");
            CareSetting setting = setting(section.getString(rawId + ".setting", "HOSPITAL"), id);
            long benefit = money(section.getString(rawId + ".medicare-benefit", "0"), id);
            long copay = money(section.getString(rawId + ".pharmacy-copay", "0"), id);
            if (setting == CareSetting.HOSPITAL && benefit <= 0) throw new IllegalArgumentException(
                    "Hospital treatment " + id + " needs a Medicare benefit");
            loaded.put(id, new TreatmentDefinition(id, names(section, rawId), item, ingredients,
                    setting, benefit, copay));
        }
        if (loaded.isEmpty()) throw new IllegalArgumentException("At least one treatment must be configured");
        return Map.copyOf(loaded);
    }

    private static Map<String, HealthConditionDefinition> loadConditions(
            ConfigurationSection section, Map<String, TreatmentDefinition> treatments) {
        Map<String, HealthConditionDefinition> loaded = new LinkedHashMap<>();
        for (String rawId : section.getKeys(false)) {
            String id = validId(rawId, "condition");
            String treatment = normalize(section.getString(rawId + ".treatment", ""));
            if (!treatments.containsKey(treatment)) throw new IllegalArgumentException(
                    "Unknown treatment " + treatment + " for condition " + id);
            CareSetting setting = setting(section.getString(rawId + ".setting", "HOSPITAL"), id);
            if (treatments.get(treatment).careSetting() != setting) throw new IllegalArgumentException(
                    "Care setting differs between condition and treatment for " + id);
            List<String> symptoms = section.getStringList(rawId + ".symptoms").stream()
                    .map(HealthRegistry::normalize).toList();
            if (symptoms.isEmpty()) throw new IllegalArgumentException("Condition " + id + " has no symptoms");
            double radius = bounded(section.getDouble(rawId + ".transmission.radius", 0),
                    0, 32, rawId + ".transmission.radius");
            double chance = probability(section.getDouble(rawId + ".transmission.chance", 0),
                    rawId + ".transmission.chance");
            ConfigurationSection exposure = section.getConfigurationSection(rawId + ".exposures");
            Map<String, Double> chances = new LinkedHashMap<>();
            if (exposure != null) for (String trigger : exposure.getKeys(false)) chances.put(
                    validId(trigger, "exposure"), probability(exposure.getDouble(trigger), trigger));
            loaded.put(id, new HealthConditionDefinition(id, names(section, rawId), symptoms,
                    treatment, setting, radius, chance, chances));
        }
        if (loaded.isEmpty()) throw new IllegalArgumentException("At least one condition must be configured");
        return Map.copyOf(loaded);
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

    private static String localized(Map<String, String> names, String locale, String fallback) {
        return names.getOrDefault(locale, names.getOrDefault("en_US", fallback));
    }

    private static ConfigurationSection required(YamlConfiguration configuration, String path) {
        ConfigurationSection section = configuration.getConfigurationSection(path);
        if (section == null) throw new IllegalArgumentException("health.yml does not contain " + path);
        return section;
    }

    private static Material material(String name, String treatment) {
        Material material = Material.matchMaterial(name);
        if (material == null || material.isAir()) throw new IllegalArgumentException(
                "Invalid material " + name + " for treatment " + treatment);
        return material;
    }

    private static CareSetting setting(String name, String id) {
        try {
            return CareSetting.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid care setting for " + id, exception);
        }
    }

    private static long money(String amount, String id) {
        try {
            return Money.parseCents(amount);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid health amount for " + id, exception);
        }
    }

    private static String validId(String raw, String type) {
        String id = normalize(raw);
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid " + type + " id: " + raw);
        return id;
    }

    private static String normalize(String id) {
        return id == null ? "" : id.toLowerCase(Locale.ROOT);
    }

    private static int bounded(int value, int minimum, int maximum, String path) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(
                path + " must be from " + minimum + " to " + maximum);
        return value;
    }

    private static double bounded(double value, double minimum, double maximum, String path) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) throw new IllegalArgumentException(
                path + " must be from " + minimum + " to " + maximum);
        return value;
    }

    private static double probability(double value, String path) {
        return bounded(value, 0, 1, path);
    }
}
