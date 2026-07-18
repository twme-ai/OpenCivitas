package dev.opencivitas.chat;

import dev.opencivitas.database.Database;
import dev.opencivitas.message.MessageService;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class ChatRouter implements Listener {
    private final JavaPlugin plugin;
    private final Database database;
    private final ChatRepository chat;
    private final ChatPolicy policy;
    private final MessageService messages;
    private final ConcurrentHashMap<UUID, ChatChannel> preferences = new ConcurrentHashMap<>();

    public ChatRouter(
            JavaPlugin plugin,
            Database database,
            ChatRepository chat,
            ChatPolicy policy,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.chat = chat;
        this.policy = policy;
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

    public void broadcastSpecial(String formatKey, Player sender, Component content) {
        for (Player recipient : Bukkit.getOnlinePlayers()) recipient.sendMessage(messages.component(
                recipient, formatKey, Placeholder.unparsed("player", sender.getName()),
                Placeholder.component("message", content)));
        Bukkit.getConsoleSender().sendMessage(messages.component("en_US", formatKey,
                Placeholder.unparsed("player", sender.getName()), Placeholder.component("message", content)));
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
        database.submit(() -> new JoinState(chat.preference(playerId), chat.unreadMail(playerId)))
                .whenComplete((state, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Could not load chat state", error);
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player player = Bukkit.getPlayer(playerId);
                        if (player == null) return;
                        preferences.put(playerId, state.channel());
                        if (state.unreadMail() > 0) messages.send(player, "chat.mail-notice",
                                Placeholder.unparsed("count", Integer.toString(state.unreadMail())));
                    });
                });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        preferences.remove(event.getPlayer().getUniqueId());
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
        List<Player> selected = new ArrayList<>(recipients);
        if (selected.stream().noneMatch(player -> player.getUniqueId().equals(sender.getUniqueId()))) {
            selected.add(sender);
        }
        for (Player recipient : selected) recipient.sendMessage(messages.component(recipient,
                "chat.format." + channel.name().toLowerCase(java.util.Locale.ROOT),
                Placeholder.unparsed("player", sender.getName()), Placeholder.component("message", content)));
        Bukkit.getConsoleSender().sendMessage(messages.component("en_US",
                "chat.format." + channel.name().toLowerCase(java.util.Locale.ROOT),
                Placeholder.unparsed("player", sender.getName()), Placeholder.component("message", content)));
        long otherRecipients = selected.stream()
                .filter(player -> !player.getUniqueId().equals(sender.getUniqueId())).count();
        if (otherRecipients == 0 && channel != ChatChannel.GLOBAL) messages.send(sender, "chat.no-recipients");
    }

    private record JoinState(ChatChannel channel, int unreadMail) {
    }
}
