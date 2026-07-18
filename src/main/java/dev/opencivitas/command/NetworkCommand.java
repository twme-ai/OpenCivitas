package dev.opencivitas.command;

import dev.opencivitas.message.MessageService;
import dev.opencivitas.network.NetworkNode;
import dev.opencivitas.network.NetworkPlayer;
import dev.opencivitas.network.NetworkService;
import dev.opencivitas.network.NetworkSnapshot;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;

public final class NetworkCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final NetworkService network;
    private final MessageService messages;

    public NetworkCommand(JavaPlugin plugin, NetworkService network, MessageService messages) {
        this.plugin = plugin;
        this.network = network;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("opencivitas.network.inspect")) {
            messages.send(sender, "error.no-permission");
            return true;
        }
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "status" -> status(sender, args);
            case "servers" -> servers(sender, args);
            case "who" -> who(sender, args);
            default -> usage(sender);
        };
    }

    private boolean status(CommandSender sender, String[] args) {
        if (args.length > 1) return usage(sender);
        messages.send(sender, "network.status",
                Placeholder.unparsed("node", network.nodeDisplayName()),
                Placeholder.unparsed("id", network.nodeId()),
                Placeholder.component("state", messages.component(sender,
                        !network.enabled() ? "network.disabled"
                                : !network.configured() ? "network.misconfigured"
                                : !network.active() ? "network.isolated"
                                : network.connected() ? "network.connected" : "network.disconnected")));
        return true;
    }

    private boolean servers(CommandSender sender, String[] args) {
        if (args.length > 1) return usage(sender);
        if (!requireEnabled(sender)) return true;
        network.snapshot().whenComplete((snapshot, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || !plugin.isEnabled() || !network.connected()) {
                if (plugin.isEnabled()) messages.send(sender, "network.unavailable");
                return;
            }
            messages.send(sender, "network.servers-header");
            if (snapshot.nodes().isEmpty()) {
                messages.send(sender, "network.servers-empty");
                return;
            }
            for (NetworkNode node : snapshot.nodes()) messages.send(sender, "network.server-entry",
                    Placeholder.unparsed("server", node.displayName()),
                    Placeholder.unparsed("id", node.id()),
                    Placeholder.unparsed("players", Integer.toString(node.onlinePlayers())));
        }));
        return true;
    }

    private boolean who(CommandSender sender, String[] args) {
        if (args.length != 2 || args[1].isBlank()) return usage(sender);
        if (!requireEnabled(sender)) return true;
        String query = args[1];
        network.snapshot().whenComplete((snapshot, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || !plugin.isEnabled() || !network.connected()) {
                if (plugin.isEnabled()) messages.send(sender, "network.unavailable");
                return;
            }
            NetworkPlayer exact = snapshot.players().stream()
                    .filter(player -> player.name().equalsIgnoreCase(query)
                            || player.uuid().toString().equalsIgnoreCase(query))
                    .findFirst().orElse(null);
            if (exact == null) {
                messages.send(sender, "network.player-offline",
                        Placeholder.unparsed("player", query));
                return;
            }
            String server = snapshot.nodes().stream()
                    .filter(node -> node.id().equals(exact.nodeId()))
                    .map(NetworkNode::displayName).findFirst().orElse(exact.nodeId());
            messages.send(sender, "network.player-location",
                    Placeholder.unparsed("player", exact.name()),
                    Placeholder.unparsed("uuid", exact.uuid().toString()),
                    Placeholder.unparsed("server", server));
        }));
        return true;
    }

    private boolean requireEnabled(CommandSender sender) {
        if (!network.enabled() || !network.configured()) {
            messages.send(sender, "network.not-configured");
            return false;
        }
        return true;
    }

    private boolean usage(CommandSender sender) {
        messages.send(sender, "network.usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("status", "servers", "who").stream()
                .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}
