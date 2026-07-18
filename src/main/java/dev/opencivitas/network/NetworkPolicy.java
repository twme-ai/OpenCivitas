package dev.opencivitas.network;

import dev.opencivitas.chat.ChatChannel;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class NetworkPolicy {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");
    private static final Pattern ENVIRONMENT = Pattern.compile("[A-Z_][A-Z0-9_]{0,63}");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9][a-z0-9:._-]{0,47}");

    private final boolean enabled;
    private final String nodeId;
    private final String nodeDisplayName;
    private final String namespace;
    private final String uriEnvironment;
    private final URI redisUri;
    private final Duration heartbeat;
    private final Duration presenceTtl;
    private final Duration reconnectDelay;
    private final Set<ChatChannel> bridgedChannels;

    public NetworkPolicy(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "network.yml");
        if (!file.exists()) plugin.saveResource("network.yml", false);
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);

        enabled = configuration.getBoolean("enabled", false);
        nodeId = id(configuration.getString("node.id", "city"), "node.id");
        nodeDisplayName = text(configuration.getString("node.display-name", "City"),
                64, "node.display-name");
        namespace = match(configuration.getString("redis.namespace", "opencivitas"),
                NAMESPACE, "redis.namespace");
        uriEnvironment = match(configuration.getString(
                "redis.uri-environment", "OPENCIVITAS_REDIS_URL"),
                ENVIRONMENT, "redis.uri-environment");
        redisUri = enabled ? redisUri(System.getenv(uriEnvironment)).orElse(null) : null;
        heartbeat = seconds(configuration.getLong("heartbeat-seconds", 15),
                5, 300, "heartbeat-seconds");
        presenceTtl = seconds(configuration.getLong("presence-ttl-seconds", 45),
                heartbeat.toSeconds() * 2, 900, "presence-ttl-seconds");
        reconnectDelay = seconds(configuration.getLong("reconnect-seconds", 8),
                1, 300, "reconnect-seconds");
        bridgedChannels = channels(configuration);
    }

    public boolean enabled() {
        return enabled;
    }

    public String nodeId() {
        return nodeId;
    }

    public String nodeDisplayName() {
        return nodeDisplayName;
    }

    public String namespace() {
        return namespace;
    }

    public String uriEnvironment() {
        return uriEnvironment;
    }

    public Optional<URI> redisUri() {
        return Optional.ofNullable(redisUri);
    }

    public Duration heartbeat() {
        return heartbeat;
    }

    public Duration presenceTtl() {
        return presenceTtl;
    }

    public Duration reconnectDelay() {
        return reconnectDelay;
    }

    public boolean bridges(ChatChannel channel) {
        return bridgedChannels.contains(channel);
    }

    public Set<ChatChannel> bridgedChannels() {
        return bridgedChannels;
    }

    private static Set<ChatChannel> channels(YamlConfiguration configuration) {
        EnumSet<ChatChannel> channels = EnumSet.noneOf(ChatChannel.class);
        for (String raw : configuration.getStringList("bridged-channels")) {
            ChatChannel channel;
            try {
                channel = ChatChannel.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown bridged channel: " + raw, exception);
            }
            if (channel == ChatChannel.LOCAL || channel == ChatChannel.MURMUR) {
                throw new IllegalArgumentException(channel.name().toLowerCase(Locale.ROOT)
                        + " is a proximity channel and cannot be bridged");
            }
            channels.add(channel);
        }
        if (enabled(configuration) && channels.isEmpty()) {
            throw new IllegalArgumentException("bridged-channels must not be empty when networking is enabled");
        }
        return Set.copyOf(channels);
    }

    private static boolean enabled(YamlConfiguration configuration) {
        return configuration.getBoolean("enabled", false);
    }

    private static Optional<URI> redisUri(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        if (scheme == null || !Set.of("redis", "rediss").contains(scheme.toLowerCase(Locale.ROOT))
                || uri.getHost() == null) {
            return Optional.empty();
        }
        return Optional.of(uri);
    }

    private static String id(String value, String path) {
        return match(value == null ? null : value.toLowerCase(Locale.ROOT), ID, path);
    }

    private static String match(String value, Pattern pattern, String path) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + path);
        }
        return value;
    }

    private static String text(String value, int maximum, String path) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid " + path);
        }
        return value;
    }

    private static Duration seconds(long value, long minimum, long maximum, String path) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(path + " must be from " + minimum + " to " + maximum);
        }
        return Duration.ofSeconds(value);
    }
}
