package dev.opencivitas.chat;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.Duration;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class ChatPolicy {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");

    private final double localRadius;
    private final double murmurRadius;
    private final Duration advertisementCooldown;
    private final int maximumMessageLength;
    private final int mailPageSize;
    private final ZoneId timeZone;
    private final Map<ChatChannel, DepartmentChannelDefinition> departments;

    public ChatPolicy(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "chat.yml");
        if (!file.exists()) plugin.saveResource("chat.yml", false);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        localRadius = bounded(configuration.getDouble("local-radius", 100), 1, 1_000, "local-radius");
        murmurRadius = bounded(configuration.getDouble("murmur-radius", 8), 1, localRadius, "murmur-radius");
        advertisementCooldown = Duration.ofSeconds((long) bounded(
                configuration.getLong("advertisement-cooldown-seconds", 600),
                1, 86_400, "advertisement-cooldown-seconds"));
        maximumMessageLength = (int) bounded(configuration.getLong("maximum-message-length", 500),
                1, 500, "maximum-message-length");
        mailPageSize = (int) bounded(configuration.getLong("mail-page-size", 10),
                1, 50, "mail-page-size");
        try {
            timeZone = ZoneId.of(configuration.getString("time-zone", "UTC"));
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("chat.yml time-zone is not a valid zone id", exception);
        }
        departments = loadDepartments(configuration.getConfigurationSection("department-channels"));
    }

    public double radius(ChatChannel channel) {
        return switch (channel) {
            case LOCAL -> localRadius;
            case MURMUR -> murmurRadius;
            default -> -1;
        };
    }

    public Duration advertisementCooldown() {
        return advertisementCooldown;
    }

    public int maximumMessageLength() {
        return maximumMessageLength;
    }

    public int mailPageSize() {
        return mailPageSize;
    }

    public ZoneId timeZone() {
        return timeZone;
    }

    public Optional<DepartmentChannelDefinition> department(ChatChannel channel) {
        return Optional.ofNullable(departments.get(channel));
    }

    private static Map<ChatChannel, DepartmentChannelDefinition> loadDepartments(ConfigurationSection section) {
        if (section == null) throw new IllegalArgumentException("chat.yml does not contain department-channels");
        Map<ChatChannel, DepartmentChannelDefinition> loaded = new EnumMap<>(ChatChannel.class);
        for (String raw : section.getKeys(false)) {
            ChatChannel channel = switch (raw.toLowerCase(Locale.ROOT)) {
                case "doj" -> ChatChannel.DOJ;
                case "senate" -> ChatChannel.SENATE;
                case "judiciary" -> ChatChannel.JUDICIARY;
                default -> throw new IllegalArgumentException("Unknown department channel: " + raw);
            };
            List<String> jobs = ids(section.getStringList(raw + ".jobs"), raw + ".jobs");
            List<String> offices = ids(section.getStringList(raw + ".offices"), raw + ".offices");
            if (jobs.isEmpty() && offices.isEmpty()) throw new IllegalArgumentException(
                    "Department channel " + raw + " needs at least one job or office");
            loaded.put(channel, new DepartmentChannelDefinition(channel, jobs, offices));
        }
        return Map.copyOf(loaded);
    }

    private static List<String> ids(List<String> values, String path) {
        return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).peek(value -> {
            if (!ID.matcher(value).matches()) throw new IllegalArgumentException("Invalid id in " + path + ": " + value);
        }).distinct().toList();
    }

    private static double bounded(double value, double minimum, double maximum, String path) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) throw new IllegalArgumentException(
                path + " must be from " + minimum + " to " + maximum);
        return value;
    }
}
