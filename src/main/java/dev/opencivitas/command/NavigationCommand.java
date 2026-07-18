package dev.opencivitas.command;

import dev.opencivitas.database.Database;
import dev.opencivitas.message.MessageService;
import dev.opencivitas.navigation.NavigationOperation;
import dev.opencivitas.navigation.NavigationPolicy;
import dev.opencivitas.navigation.NavigationRepository;
import dev.opencivitas.navigation.NavigationResult;
import dev.opencivitas.navigation.NavigationRoute;
import dev.opencivitas.navigation.NavigationService;
import dev.opencivitas.navigation.SavedLocation;
import dev.opencivitas.navigation.SafeTeleportService;
import dev.opencivitas.navigation.TeleportOutcome;
import dev.opencivitas.property.Property;
import dev.opencivitas.property.PropertyRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

public final class NavigationCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final Database database;
    private final NavigationRepository navigation;
    private final NavigationPolicy policy;
    private final NavigationService gps;
    private final SafeTeleportService teleports;
    private final PropertyRegistry properties;
    private final MessageService messages;

    public NavigationCommand(
            JavaPlugin plugin,
            Database database,
            NavigationRepository navigation,
            NavigationPolicy policy,
            NavigationService gps,
            SafeTeleportService teleports,
            PropertyRegistry properties,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.navigation = navigation;
        this.policy = policy;
        this.gps = gps;
        this.teleports = teleports;
        this.properties = properties;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "sethome" -> setHome(sender, args);
            case "home", "homes" -> home(sender, args);
            case "delhome" -> deleteHome(sender, args);
            case "civicwarp" -> civicWarp(sender, args);
            case "coords" -> coordinates(sender, args);
            case "sendcoords" -> sendCoordinates(sender, args);
            case "map" -> map(sender, args);
            case "gps" -> gps(sender, args);
            case "directions" -> directions(sender, args);
            default -> policy.warpForCommand(name).isPresent()
                    ? warp(sender, args, policy.warpForCommand(name).orElseThrow()) : false;
        };
    }

    private boolean setHome(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 1 || !NavigationPolicy.validId(args[0])) return usage(sender, "/sethome <name>");
        SavedLocation location = saved(args[0], player.getLocation());
        complete(sender, database.submit(() -> navigation.setHome(
                player.getUniqueId(), location, policy.maximumHomes(), System.currentTimeMillis())), operation -> {
            if (operation.result() != NavigationResult.SUCCESS) error(sender, operation.result());
            else messages.send(sender, "navigation.home-set", Placeholder.unparsed("home", location.id()));
        });
        return true;
    }

    private boolean home(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length == 0) {
            complete(sender, database.submit(() -> navigation.homes(player.getUniqueId())), homes -> {
                messages.send(sender, "navigation.homes-header");
                if (homes.isEmpty()) messages.send(sender, "navigation.homes-empty");
                for (SavedLocation home : homes) messages.send(sender, "navigation.home-entry",
                        Placeholder.unparsed("home", home.id()),
                        Placeholder.unparsed("world", home.world()),
                        Placeholder.unparsed("x", coordinate(home.x())),
                        Placeholder.unparsed("y", coordinate(home.y())),
                        Placeholder.unparsed("z", coordinate(home.z())));
            });
            return true;
        }
        if (args.length != 1) return usage(sender, "/home [name]");
        complete(sender, database.submit(() -> navigation.home(player.getUniqueId(), args[0])), found -> {
            if (found.isEmpty()) messages.send(sender, "navigation.home-not-found");
            else teleport(player, found.orElseThrow(), "navigation.home-arrived");
        });
        return true;
    }

    private boolean deleteHome(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 1) return usage(sender, "/delhome <name>");
        complete(sender, database.submit(() -> navigation.deleteHome(player.getUniqueId(), args[0])), result -> {
            if (result != NavigationResult.SUCCESS) error(sender, result);
            else messages.send(sender, "navigation.home-deleted", Placeholder.unparsed("home", args[0]));
        });
        return true;
    }

    private boolean civicWarp(CommandSender sender, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
            complete(sender, database.submit(navigation::warps), warps -> {
                messages.send(sender, "navigation.warps-header");
                if (warps.isEmpty()) messages.send(sender, "navigation.warps-empty");
                for (SavedLocation warp : warps) messages.send(sender, "navigation.warp-entry",
                        Placeholder.unparsed("warp", warp.id()),
                        Placeholder.unparsed("world", warp.world()),
                        Placeholder.unparsed("x", coordinate(warp.x())),
                        Placeholder.unparsed("y", coordinate(warp.y())),
                        Placeholder.unparsed("z", coordinate(warp.z())));
            });
            return true;
        }
        if (!sender.hasPermission("opencivitas.navigation.configure")) {
            messages.send(sender, "error.no-permission");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            if (!(sender instanceof Player player)) return playerOnly(sender);
            if (!NavigationPolicy.validId(args[1])) return usage(sender, "/civicwarp set <id>");
            SavedLocation warp = saved(args[1], player.getLocation());
            complete(sender, database.submit(() -> navigation.setWarp(
                    warp, player.getUniqueId(), System.currentTimeMillis())), operation -> {
                if (operation.result() != NavigationResult.SUCCESS) error(sender, operation.result());
                else messages.send(sender, "navigation.warp-set", Placeholder.unparsed("warp", warp.id()));
            });
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            complete(sender, database.submit(() -> navigation.deleteWarp(args[1])), result -> {
                if (result != NavigationResult.SUCCESS) error(sender, result);
                else messages.send(sender, "navigation.warp-deleted", Placeholder.unparsed("warp", args[1]));
            });
            return true;
        }
        return usage(sender, "/civicwarp <list|set|remove>");
    }

    private boolean warp(CommandSender sender, String[] args, String warpId) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 0) return usage(sender, "/" + warpId);
        complete(sender, database.submit(() -> navigation.warp(warpId)), found -> {
            if (found.isEmpty()) messages.send(sender, "navigation.warp-not-found",
                    Placeholder.unparsed("warp", warpId));
            else teleport(player, found.orElseThrow(), "navigation.warp-arrived");
        });
        return true;
    }

    private boolean coordinates(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 0) return usage(sender, "/coords");
        sendLocation(sender, "navigation.coordinates", player.getLocation());
        return true;
    }

    private boolean sendCoordinates(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 1) return usage(sender, "/sendcoords <player>");
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            messages.send(sender, "chat.target-offline");
            return true;
        }
        Location location = player.getLocation();
        sendLocation(target, "navigation.coordinates-received", location,
                Placeholder.unparsed("player", player.getName()));
        messages.send(sender, "navigation.coordinates-sent", Placeholder.unparsed("player", target.getName()));
        return true;
    }

    private boolean map(CommandSender sender, String[] args) {
        if (args.length != 0) return usage(sender, "/map");
        if (policy.mapUrl().isEmpty()) {
            messages.send(sender, "navigation.map-unavailable");
            return true;
        }
        Component link = Component.text(policy.mapUrl()).clickEvent(ClickEvent.openUrl(policy.mapUrl()));
        messages.send(sender, "navigation.map", Placeholder.component("url", link));
        return true;
    }

    private boolean gps(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length == 1 && args[0].equalsIgnoreCase("stop")) {
            if (gps.stop(player)) messages.send(sender, "navigation.gps-stopped");
            else messages.send(sender, "navigation.gps-not-active");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            Property property = properties.find(args[1]).orElse(null);
            if (property == null) {
                messages.send(sender, "navigation.plot-not-found");
            } else if (!gps.start(player, property)) {
                messages.send(sender, "navigation.world-unavailable");
            } else {
                messages.send(sender, "navigation.gps-started",
                        Placeholder.unparsed("plot", property.plotId()));
            }
            return true;
        }
        return usage(sender, "/gps <start <plot>|stop>");
    }

    private boolean directions(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 1) return usage(sender, "/directions <plot>");
        Property property = properties.find(args[0]).orElse(null);
        if (property == null) {
            messages.send(sender, "navigation.plot-not-found");
            return true;
        }
        NavigationRoute route = gps.route(player, property);
        if (route == null) {
            messages.send(sender, "navigation.world-unavailable");
        } else if (!player.getWorld().getName().equals(property.worldName())) {
            messages.send(sender, "navigation.directions-other-world",
                    Placeholder.unparsed("plot", property.plotId()),
                    Placeholder.unparsed("world", property.worldName()));
        } else {
            messages.send(sender, "navigation.directions",
                    Placeholder.unparsed("plot", property.plotId()),
                    Placeholder.unparsed("distance", Long.toString(Math.round(route.distance()))),
                    Placeholder.component("direction", messages.component(sender,
                            "navigation.direction." + route.direction())));
        }
        return true;
    }

    private void teleport(Player player, SavedLocation saved, String successKey) {
        teleports.teleport(player, saved, outcome -> messages.send(player, switch (outcome) {
            case SUCCESS -> successKey;
            case WORLD_UNAVAILABLE -> "navigation.world-unavailable";
            case UNSAFE_DESTINATION -> "navigation.unsafe-destination";
            case FAILED -> "navigation.teleport-failed";
        }, Placeholder.unparsed("destination", saved.id())));
    }

    private void sendLocation(CommandSender recipient, String key, Location location,
                              net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... extra) {
        var resolvers = new java.util.ArrayList<net.kyori.adventure.text.minimessage.tag.resolver.TagResolver>();
        resolvers.add(Placeholder.unparsed("world", location.getWorld().getName()));
        resolvers.add(Placeholder.unparsed("x", coordinate(location.getX())));
        resolvers.add(Placeholder.unparsed("y", coordinate(location.getY())));
        resolvers.add(Placeholder.unparsed("z", coordinate(location.getZ())));
        resolvers.addAll(Arrays.asList(extra));
        messages.send(recipient, key, resolvers.toArray(
                net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[]::new));
    }

    private void error(CommandSender sender, NavigationResult result) {
        messages.send(sender, switch (result) {
            case CITIZEN_NOT_FOUND -> "error.player-not-found";
            case HOME_NOT_FOUND -> "navigation.home-not-found";
            case HOME_LIMIT_REACHED -> "navigation.home-limit";
            case WARP_NOT_FOUND -> "navigation.warp-not-found-generic";
            case INVALID_NAME -> "navigation.invalid-name";
            case SUCCESS -> "navigation.operation-complete";
        }, Placeholder.unparsed("maximum", Integer.toString(policy.maximumHomes())));
    }

    private <T> void complete(CommandSender sender, CompletableFuture<T> future,
                              java.util.function.Consumer<T> success) {
        future.whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                plugin.getLogger().log(Level.SEVERE, "Navigation operation failed", cause);
                messages.send(sender, "error.database");
            } else {
                success.accept(result);
            }
        }));
    }

    private boolean playerOnly(CommandSender sender) {
        messages.send(sender, "error.player-only");
        return true;
    }

    private boolean usage(CommandSender sender, String usage) {
        messages.send(sender, "error.usage", Placeholder.unparsed("usage", usage));
        return true;
    }

    private static SavedLocation saved(String id, Location location) {
        return new SavedLocation(id.toLowerCase(Locale.ROOT), location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    private static String coordinate(double value) {
        return "%.1f".formatted(Locale.ROOT, value);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("sendcoords") && args.length == 1) return onlineNames(args[0]);
        if (name.equals("gps") && args.length == 1) return filter(List.of("start", "stop"), args[0]);
        if (name.equals("civicwarp") && args.length == 1) return filter(
                List.of("list", "set", "remove"), args[0]);
        return List.of();
    }

    private static List<String> onlineNames(String prefix) {
        return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), prefix);
    }

    private static List<String> filter(Collection<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized))
                .sorted().toList();
    }
}
