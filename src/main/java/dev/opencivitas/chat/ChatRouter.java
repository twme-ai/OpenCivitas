package dev.opencivitas.chat;

import dev.opencivitas.database.Database;
import dev.opencivitas.message.MessageService;
import dev.opencivitas.network.NetworkEnvelope;
import dev.opencivitas.network.NetworkService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class ChatRouter implements Listener {
    private final JavaPlugin plugin;
    private final Database database;
    private final ChatRepository chat;
    private final ChatPolicy policy;
    private final NetworkService network;
    private final MessageService messages;
    private final ConcurrentHashMap<UUID, ChatChannel> preferences = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<UUID>> ignoredPlayers = new ConcurrentHashMap<>();
    private final Set<UUID> loadedIgnoreState = ConcurrentHashMap.newKeySet();

    public ChatRouter(
            JavaPlugin plugin,
            Database database,
            ChatRepository chat,
            ChatPolicy policy,
            NetworkService network,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.chat = chat;
        this.policy = policy;
        this.network = network;
        this.messages = messages;
    }

    public ChatChannel channel(UUID playerId) {
        return preferences.getOrDefault(playerId, ChatChannel.GLOBAL);
    }

    public void select(Player player, ChatChannel channel) {
        preferences.put(player.getUniqueId(), channel);
        database.submit(() -> chat.setPreference(player.getUniqueId(), channel, System.currentTimeMillis()))
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.WARNING, "Could not persist chat preference", error);
                    return ChatResult.CITIZEN_NOT_FOUND;
                });
        messages.send(player, "chat.channel-selected",
                Placeholder.component("channel", messages.component(player,
                        "chat.channel." + channel.name().toLowerCase(java.util.Locale.ROOT))));
    }

    public void updateIgnore(UUID playerId, UUID ignoredId, boolean ignored) {
        ignoredPlayers.compute(playerId, (key, current) -> {
            Set<UUID> updated = new HashSet<>(current == null ? Set.of() : current);
            if (ignored) updated.add(ignoredId);
            else updated.remove(ignoredId);
            return Set.copyOf(updated);
        });
    }

    public void broadcastSpecial(String formatKey, Player sender, Component content) {
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (!isIgnoredBy(recipient.getUniqueId(), sender.getUniqueId())) {
                recipient.sendMessage(messages.component(
                        recipient, formatKey, Placeholder.unparsed("player", sender.getName()),
                        Placeholder.component("message", content)));
            }
        }
        Bukkit.getConsoleSender().sendMessage(messages.component("en_US", formatKey,
                Placeholder.unparsed("player", sender.getName()), Placeholder.component("message", content)));
    }

    public void receiveNetworkChat(NetworkEnvelope envelope) {
        DepartmentChannelDefinition department = policy.department(envelope.channel()).orElse(null);
        if (department == null) {
            if (envelope.channel() == ChatChannel.GLOBAL) {
                deliverNetwork(envelope, Bukkit.getOnlinePlayers());
            }
            return;
        }
        database.submit(() -> chat.departmentMembers(department, System.currentTimeMillis()))
                .whenComplete((members, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Could not resolve network department chat", error);
                        return;
                    }
                    deliverNetwork(envelope, Bukkit.getOnlinePlayers().stream()
                            .filter(player -> members.contains(player.getUniqueId())).toList());
                }));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        event.setCancelled(true);
        Player sender = event.getPlayer();
        Component content = event.message();
        if (PlainTextComponentSerializer.plainText().serialize(content).length() > policy.maximumMessageLength()) {
            Bukkit.getScheduler().runTask(plugin, () -> messages.send(sender, "chat.message-too-long",
                    Placeholder.unparsed("maximum", Integer.toString(policy.maximumMessageLength()))));
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> route(sender, channel(sender.getUniqueId()), content));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        loadedIgnoreState.remove(playerId);
        database.submit(() -> new JoinState(
                        chat.preference(playerId), chat.unreadMail(playerId), chat.ignoredIds(playerId)))
                .whenComplete((state, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Could not load chat state", error);
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player player = Bukkit.getPlayer(playerId);
                        if (player == null) return;
                        preferences.put(playerId, state.channel());
                        ignoredPlayers.put(playerId, state.ignoredPlayers());
                        loadedIgnoreState.add(playerId);
                        if (state.unreadMail() > 0) messages.send(player, "chat.mail-notice",
                                Placeholder.unparsed("count", Integer.toString(state.unreadMail())));
                    });
                });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        preferences.remove(event.getPlayer().getUniqueId());
        ignoredPlayers.remove(event.getPlayer().getUniqueId());
        loadedIgnoreState.remove(event.getPlayer().getUniqueId());
    }

    private void route(Player sender, ChatChannel channel, Component content) {
        DepartmentChannelDefinition department = policy.department(channel).orElse(null);
        if (department != null) {
            database.submit(() -> chat.departmentMembers(department, System.currentTimeMillis()))
                    .whenComplete((members, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null) {
                            plugin.getLogger().log(Level.WARNING, "Could not resolve department chat", error);
                            messages.send(sender, "error.database");
                            return;
                        }
                        if (!members.contains(sender.getUniqueId())) {
                            select(sender, ChatChannel.GLOBAL);
                            messages.send(sender, "chat.department-no-longer-authorized");
                            return;
                        }
                        deliver(sender, channel, content, Bukkit.getOnlinePlayers().stream()
                                .filter(player -> members.contains(player.getUniqueId())).toList());
                    }));
            return;
        }
        if (channel == ChatChannel.GLOBAL) {
            deliver(sender, channel, content, Bukkit.getOnlinePlayers());
            return;
        }
        double radiusSquared = policy.radius(channel) * policy.radius(channel);
        List<? extends Player> recipients = Bukkit.getOnlinePlayers().stream().filter(recipient ->
                recipient.getWorld().equals(sender.getWorld())
                        && recipient.getLocation().distanceSquared(sender.getLocation()) <= radiusSquared).toList();
        deliver(sender, channel, content, recipients);
    }

    private void deliver(
            Player sender, ChatChannel channel, Component content, Collection<? extends Player> recipients) {
        List<Player> selected = new ArrayList<>(recipients.stream()
                .filter(recipient -> !isIgnoredBy(recipient.getUniqueId(), sender.getUniqueId()))
                .toList());
        if (selected.stream().noneMatch(player -> player.getUniqueId().equals(sender.getUniqueId()))) {
            selected.add(sender);
        }
        for (Player recipient : selected) recipient.sendMessage(messages.component(recipient,
                "chat.format." + channel.name().toLowerCase(java.util.Locale.ROOT),
                Placeholder.unparsed("player", sender.getName()), Placeholder.component("message", content)));
        Bukkit.getConsoleSender().sendMessage(messages.component("en_US",
                "chat.format." + channel.name().toLowerCase(java.util.Locale.ROOT),
                Placeholder.unparsed("player", sender.getName()), Placeholder.component("message", content)));
        if (network.bridges(channel)) network.publishChat(sender, channel,
                PlainTextComponentSerializer.plainText().serialize(content));
        long otherRecipients = selected.stream()
                .filter(player -> !player.getUniqueId().equals(sender.getUniqueId())).count();
        if (otherRecipients == 0 && channel != ChatChannel.GLOBAL && !network.bridges(channel)) {
            messages.send(sender, "chat.no-recipients");
        }
    }

    private void deliverNetwork(NetworkEnvelope envelope, Collection<? extends Player> recipients) {
        Component content = Component.text(envelope.content());
        String key = "network.chat-format."
                + envelope.channel().name().toLowerCase(java.util.Locale.ROOT);
        for (Player recipient : recipients) {
            if (!isIgnoredBy(recipient.getUniqueId(), envelope.playerId())) {
                recipient.sendMessage(messages.component(recipient, key,
                        Placeholder.unparsed("server", envelope.sourceDisplayName()),
                        Placeholder.unparsed("player", envelope.playerName()),
                        Placeholder.component("message", content)));
            }
        }
        Bukkit.getConsoleSender().sendMessage(messages.component("en_US", key,
                Placeholder.unparsed("server", envelope.sourceDisplayName()),
                Placeholder.unparsed("player", envelope.playerName()),
                Placeholder.component("message", content)));
    }

    private boolean isIgnoredBy(UUID recipientId, UUID senderId) {
        return !recipientId.equals(senderId) && (!loadedIgnoreState.contains(recipientId)
                || ignoredPlayers.getOrDefault(recipientId, Set.of()).contains(senderId));
    }

    private record JoinState(ChatChannel channel, int unreadMail, Set<UUID> ignoredPlayers) {
    }
}
