package dev.opencivitas.network;

import dev.opencivitas.chat.ChatChannel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.RedisClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class NetworkService implements Listener, AutoCloseable {
    private static final Duration REPLAY_WINDOW = Duration.ofMinutes(2);
    private static final Duration FUTURE_SKEW = Duration.ofSeconds(30);
    private static final String DELETE_OWN_PRESENCE = """
            if redis.call('hget', KEYS[1], 'instance') == ARGV[1] then
              redis.call('del', KEYS[1])
              redis.call('srem', KEYS[2], ARGV[2])
              return 1
            end
            return 0
            """;
    private static final String UPDATE_OWN_NODE = """
            local owner = redis.call('hget', KEYS[1], 'instance')
            if owner and owner ~= ARGV[1] then
              return 0
            end
            redis.call('hset', KEYS[1],
              'instance', ARGV[1], 'display', ARGV[2],
              'online', ARGV[3], 'heartbeat', ARGV[4])
            redis.call('expire', KEYS[1], ARGV[5])
            redis.call('sadd', KEYS[2], ARGV[6])
            return 1
            """;
    private static final String DELETE_OWN_NODE = """
            if redis.call('hget', KEYS[1], 'instance') == ARGV[1] then
              redis.call('del', KEYS[1])
              redis.call('srem', KEYS[2], ARGV[2])
              return 1
            end
            return 0
            """;

    private final JavaPlugin plugin;
    private final NetworkPolicy policy;
    private final NetworkDeduplicator deduplicator = new NetworkDeduplicator(
            REPLAY_WINDOW, FUTURE_SKEW, 4_096);
    private final String instanceId = UUID.randomUUID().toString();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicLong lastFailureLog = new AtomicLong();
    private final RedisClient commands;
    private volatile Consumer<NetworkEnvelope> chatConsumer = ignored -> { };
    private volatile JedisPubSub subscription;
    private volatile boolean running;
    private volatile boolean connected;
    private Thread subscriberThread;
    private BukkitTask heartbeatTask;

    public NetworkService(JavaPlugin plugin, NetworkPolicy policy) {
        this.plugin = plugin;
        this.policy = policy;
        commands = policy.redisUri().map(RedisClient::create).orElse(null);
    }

    public void start() {
        if (!policy.enabled()) return;
        if (commands == null) {
            plugin.getLogger().warning("Cross-server networking is enabled, but "
                    + policy.uriEnvironment() + " does not contain a valid Redis URI; continuing standalone");
            return;
        }
        running = true;
        subscriberThread = Thread.ofPlatform()
                .name("opencivitas-network-subscriber")
                .daemon(true)
                .start(this::subscribeLoop);
        refreshPresence();
        heartbeatTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshPresence,
                policy.heartbeat().toSeconds() * 20L,
                policy.heartbeat().toSeconds() * 20L);
    }

    public void setChatConsumer(Consumer<NetworkEnvelope> chatConsumer) {
        this.chatConsumer = chatConsumer == null ? ignored -> { } : chatConsumer;
    }

    public boolean enabled() {
        return policy.enabled();
    }

    public boolean connected() {
        return connected;
    }

    public boolean configured() {
        return commands != null;
    }

    public boolean active() {
        return running;
    }

    public String nodeId() {
        return policy.nodeId();
    }

    public String nodeDisplayName() {
        return policy.nodeDisplayName();
    }

    public boolean bridges(ChatChannel channel) {
        return running && policy.bridges(channel);
    }

    public void publishChat(Player sender, ChatChannel channel, String content) {
        if (!running || !policy.bridges(channel)) return;
        NetworkEnvelope envelope = new NetworkEnvelope(UUID.randomUUID(), policy.nodeId(),
                policy.nodeDisplayName(), sender.getUniqueId(), sender.getName(), channel,
                content, System.currentTimeMillis());
        executor.execute(() -> runRedis("publish a network chat message",
                () -> commands.publish(chatChannel(), envelope.encode())));
    }

    public CompletableFuture<NetworkSnapshot> snapshot() {
        if (!running) return CompletableFuture.completedFuture(new NetworkSnapshot(List.of(), List.of()));
        return CompletableFuture.supplyAsync(this::loadSnapshot, executor);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!running) return;
        PlayerPresence player = presence(event.getPlayer());
        List<PlayerPresence> online = onlinePresence();
        executor.execute(() -> runRedis("publish player presence", () -> {
            updateNode(online.size(), System.currentTimeMillis());
            updatePlayer(player, System.currentTimeMillis());
        }));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!running) return;
        UUID playerId = event.getPlayer().getUniqueId();
        int onlineAfterQuit = Math.max(0, Bukkit.getOnlinePlayers().size() - 1);
        executor.execute(() -> runRedis("remove player presence", () -> {
            removePlayer(playerId);
            updateNode(onlineAfterQuit, System.currentTimeMillis());
        }));
    }

    private void refreshPresence() {
        if (!running) return;
        List<PlayerPresence> online = onlinePresence();
        executor.execute(() -> runRedis("refresh network presence", () -> {
            long now = System.currentTimeMillis();
            updateNode(online.size(), now);
            for (PlayerPresence player : online) updatePlayer(player, now);
        }));
    }

    private void updateNode(int onlinePlayers, long now) {
        String id = policy.nodeId();
        Object updated = commands.eval(UPDATE_OWN_NODE,
                List.of(nodeKey(id), nodesKey()),
                List.of(instanceId, policy.nodeDisplayName(), Integer.toString(onlinePlayers),
                        Long.toString(now), Long.toString(policy.presenceTtl().toSeconds()), id));
        if (!(updated instanceof Long result) || result != 1L) {
            throw new NodeIdCollisionException();
        }
    }

    private void updatePlayer(PlayerPresence player, long now) {
        String uuid = player.uuid().toString();
        commands.sadd(playersKey(), uuid);
        commands.hset(playerKey(player.uuid()), Map.of(
                "uuid", uuid,
                "name", player.name(),
                "node", policy.nodeId(),
                "instance", instanceId,
                "updated", Long.toString(now)));
        commands.expire(playerKey(player.uuid()), policy.presenceTtl().toSeconds());
    }

    private void removePlayer(UUID playerId) {
        commands.eval(DELETE_OWN_PRESENCE,
                List.of(playerKey(playerId), playersKey()),
                List.of(instanceId, playerId.toString()));
    }

    private void subscribeLoop() {
        while (running) {
            try (Jedis subscriber = new Jedis(policy.redisUri().orElseThrow())) {
                JedisPubSub listener = new JedisPubSub() {
                    @Override
                    public void onSubscribe(String channel, int subscribedChannels) {
                        if (!running) {
                            unsubscribe();
                            return;
                        }
                        connected = true;
                    }

                    @Override
                    public void onMessage(String channel, String message) {
                        receive(message);
                    }
                };
                subscription = listener;
                if (!running) return;
                subscriber.subscribe(listener, chatChannel());
            } catch (RuntimeException exception) {
                connected = false;
                reportFailure("subscribe to the Redis network transport");
            } finally {
                subscription = null;
            }
            if (running) {
                try {
                    Thread.sleep(policy.reconnectDelay().toMillis());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void receive(String message) {
        NetworkEnvelope envelope;
        try {
            envelope = NetworkEnvelope.decode(message);
        } catch (IllegalArgumentException exception) {
            reportFailure("decode a rejected network message");
            return;
        }
        long now = System.currentTimeMillis();
        if (envelope.sourceNode().equals(policy.nodeId())
                || !policy.bridges(envelope.channel())
                || !deduplicator.accept(envelope.messageId(), envelope.createdAt(), now)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (plugin.isEnabled()) chatConsumer.accept(envelope);
        });
    }

    private NetworkSnapshot loadSnapshot() {
        try {
            long cutoff = System.currentTimeMillis() - policy.presenceTtl().toMillis();
            List<NetworkNode> nodes = new ArrayList<>();
            Set<String> activeNodeIds = new HashSet<>();
            for (String id : commands.smembers(nodesKey())) {
                Map<String, String> values = commands.hgetAll(nodeKey(id));
                long heartbeat = number(values.get("heartbeat"), -1);
                if (heartbeat < cutoff) {
                    commands.srem(nodesKey(), id);
                    continue;
                }
                activeNodeIds.add(id);
                long online = Math.max(0, Math.min(Integer.MAX_VALUE,
                        number(values.get("online"), 0)));
                nodes.add(new NetworkNode(id, values.getOrDefault("display", id),
                        (int) online, heartbeat));
            }
            List<NetworkPlayer> players = new ArrayList<>();
            for (String rawUuid : commands.smembers(playersKey())) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(rawUuid);
                } catch (IllegalArgumentException exception) {
                    commands.srem(playersKey(), rawUuid);
                    continue;
                }
                Map<String, String> values = commands.hgetAll(playerKey(uuid));
                long updated = number(values.get("updated"), -1);
                String node = values.get("node");
                if (updated < cutoff || !activeNodeIds.contains(node)) {
                    commands.srem(playersKey(), rawUuid);
                    continue;
                }
                players.add(new NetworkPlayer(uuid, values.getOrDefault("name", rawUuid), node, updated));
            }
            nodes.sort(Comparator.comparing(NetworkNode::id));
            players.sort(Comparator.comparing(NetworkPlayer::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(NetworkPlayer::uuid));
            connected = true;
            return new NetworkSnapshot(nodes, players);
        } catch (RuntimeException exception) {
            connected = false;
            reportFailure("read network presence");
            return new NetworkSnapshot(List.of(), List.of());
        }
    }

    private void runRedis(String operation, Runnable action) {
        if (!running || commands == null) return;
        try {
            action.run();
            connected = true;
        } catch (NodeIdCollisionException exception) {
            connected = false;
            running = false;
            plugin.getLogger().severe("Another live OpenCivitas process already owns network node id '"
                    + policy.nodeId() + "'; continuing standalone on this process");
            JedisPubSub current = subscription;
            if (current != null) {
                try {
                    current.unsubscribe();
                } catch (RuntimeException ignored) {
                    // The colliding transport is already disconnected.
                }
            }
        } catch (RuntimeException exception) {
            connected = false;
            reportFailure(operation);
        }
    }

    private void reportFailure(String operation) {
        long now = System.currentTimeMillis();
        long previous = lastFailureLog.get();
        if (now - previous >= 30_000 && lastFailureLog.compareAndSet(previous, now)) {
            plugin.getLogger().warning("Could not " + operation
                    + "; standalone OpenCivitas behavior remains available and the transport will retry");
        }
    }

    private List<PlayerPresence> onlinePresence() {
        return Bukkit.getOnlinePlayers().stream().map(NetworkService::presence).toList();
    }

    private static PlayerPresence presence(Player player) {
        return new PlayerPresence(player.getUniqueId(), player.getName());
    }

    private String prefix() {
        return policy.namespace() + ":v1";
    }

    private String chatChannel() {
        return prefix() + ":chat";
    }

    private String nodesKey() {
        return prefix() + ":nodes";
    }

    private String nodeKey(String nodeId) {
        return prefix() + ":node:" + nodeId;
    }

    private String playersKey() {
        return prefix() + ":players";
    }

    private String playerKey(UUID playerId) {
        return prefix() + ":player:" + playerId;
    }

    private static long number(String value, long fallback) {
        if (value == null) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    @Override
    public void close() {
        if (!policy.enabled() || commands == null) {
            executor.shutdownNow();
            return;
        }
        if (heartbeatTask != null) heartbeatTask.cancel();
        List<UUID> online = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
        CompletableFuture<Void> cleanup = CompletableFuture.runAsync(() -> {
            for (UUID playerId : online) removePlayer(playerId);
            commands.eval(DELETE_OWN_NODE,
                    List.of(nodeKey(policy.nodeId()), nodesKey()),
                    List.of(instanceId, policy.nodeId()));
        }, executor);
        try {
            cleanup.get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Presence expires automatically if Redis is unavailable during shutdown.
        }
        running = false;
        JedisPubSub current = subscription;
        if (current != null) {
            try {
                current.unsubscribe();
            } catch (RuntimeException ignored) {
                // The subscriber is already disconnected.
            }
        }
        if (subscriberThread != null) subscriberThread.interrupt();
        commands.close();
        executor.shutdownNow();
        connected = false;
    }

    private record PlayerPresence(UUID uuid, String name) {
    }

    private static final class NodeIdCollisionException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
    }
}
