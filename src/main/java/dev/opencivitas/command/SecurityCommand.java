package dev.opencivitas.command;

import dev.opencivitas.citizen.CitizenProfile;
import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.message.MessageService;
import dev.opencivitas.security.CameraGroup;
import dev.opencivitas.security.CameraManager;
import dev.opencivitas.security.CameraViewService;
import dev.opencivitas.security.SecurityCamera;
import dev.opencivitas.security.SecurityComputer;
import dev.opencivitas.security.SecurityItems;
import dev.opencivitas.security.SecurityMenuService;
import dev.opencivitas.security.SecurityRegistry;
import dev.opencivitas.security.SecurityRepository;
import dev.opencivitas.security.SecurityResult;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class SecurityCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final Database database;
    private final CitizenRepository citizens;
    private final SecurityRepository security;
    private final SecurityRegistry registry;
    private final CameraManager cameraManager;
    private final CameraViewService views;
    private final SecurityMenuService menus;
    private final SecurityItems items;
    private final MessageService messages;

    public SecurityCommand(
            JavaPlugin plugin,
            Database database,
            CitizenRepository citizens,
            SecurityRepository security,
            SecurityRegistry registry,
            CameraManager cameraManager,
            CameraViewService views,
            SecurityMenuService menus,
            SecurityItems items,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.citizens = citizens;
        this.security = security;
        this.registry = registry;
        this.cameraManager = cameraManager;
        this.views = views;
        this.menus = menus;
        this.items = items;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return usage(sender);
        String action = args[0].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "group" -> group(sender, args);
            case "camera" -> camera(sender, args);
            case "computer" -> computer(sender, args);
            case "view" -> view(sender, args);
            case "next" -> switchView(sender, 1);
            case "previous", "prev" -> switchView(sender, -1);
            case "exit", "disconnect" -> exit(sender);
            case "give" -> give(sender, args);
            default -> usage(sender);
        };
    }

    private boolean group(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null || args.length < 2) return usage(sender);
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (args.length != 2) yield usage(sender);
                async(player, () -> security.groups(player.getUniqueId()), groups -> {
                    messages.send(player, "security.groups-header");
                    if (groups.isEmpty()) messages.send(player, "security.groups-empty");
                    for (CameraGroup group : groups) messages.send(player, "security.group-entry",
                            Placeholder.unparsed("group", group.name()));
                });
                yield true;
            }
            case "create" -> {
                if (args.length != 3) yield usage(sender);
                async(player, () -> security.createGroup(
                        player.getUniqueId(), args[2], System.currentTimeMillis()),
                        operation -> {
                            if (operation.result() != SecurityResult.SUCCESS) {
                                result(player, operation.result());
                            } else operation.value().ifPresent(group -> messages.send(
                                    player, "security.group-created",
                                    Placeholder.unparsed("group", group.name())));
                        });
                yield true;
            }
            case "delete" -> {
                if (args.length != 3) yield usage(sender);
                async(player, () -> security.deleteGroup(player.getUniqueId(), args[2]),
                        value -> result(player, value));
                yield true;
            }
            case "addcamera", "removecamera" -> {
                if (args.length != 4) yield usage(sender);
                boolean add = args[1].equalsIgnoreCase("addcamera");
                async(player, () -> add
                                ? security.addCamera(player.getUniqueId(), args[2], args[3], System.currentTimeMillis())
                                : security.removeCamera(player.getUniqueId(), args[2], args[3]),
                        value -> result(player, value));
                yield true;
            }
            default -> usage(sender);
        };
    }

    private boolean camera(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null || args.length < 2) return usage(sender);
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (args.length != 2) yield usage(sender);
                async(player, () -> security.cameras(player.getUniqueId()), cameras -> {
                    messages.send(player, "security.cameras-header");
                    if (cameras.isEmpty()) messages.send(player, "security.cameras-empty");
                    for (SecurityCamera camera : cameras) messages.send(player, "security.camera-entry",
                            Placeholder.unparsed("camera", camera.name()),
                            Placeholder.unparsed("world", camera.world()),
                            Placeholder.unparsed("x", Integer.toString((int) Math.floor(camera.x()))),
                            Placeholder.unparsed("y", Integer.toString((int) Math.floor(camera.y()))),
                            Placeholder.unparsed("z", Integer.toString((int) Math.floor(camera.z()))));
                });
                yield true;
            }
            case "rename" -> {
                if (args.length != 4) yield usage(sender);
                async(player, () -> security.renameCamera(player.getUniqueId(), args[2], args[3]),
                        operation -> {
                            if (operation.result() != SecurityResult.SUCCESS) {
                                result(player, operation.result());
                            } else operation.value().ifPresent(camera -> {
                                cameraManager.update(camera);
                                messages.send(player, "security.camera-renamed",
                                        Placeholder.unparsed("camera", camera.name()));
                            });
                        });
                yield true;
            }
            case "view" -> args.length == 3
                    ? view(sender, new String[]{"view", args[2]}) : usage(sender);
            default -> usage(sender);
        };
    }

    private boolean computer(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null || args.length < 2) return usage(sender);
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (args.length != 2) yield usage(sender);
                async(player, () -> security.computers(player.getUniqueId()), computers -> {
                    messages.send(player, "security.computers-header");
                    if (computers.isEmpty()) messages.send(player, "security.computers-empty");
                    for (SecurityComputer computer : computers) messages.send(player, "security.computer-entry",
                            Placeholder.unparsed("computer", computer.name()),
                            Placeholder.unparsed("id", Long.toString(computer.id())),
                            Placeholder.component("access", messages.component(player,
                                    computer.publicAccess()
                                            ? "security.access-public" : "security.access-private")));
                });
                yield true;
            }
            case "open" -> {
                Long id = args.length == 3 ? number(args[2]) : null;
                if (id == null) yield usage(sender);
                menus.openComputer(player, id);
                yield true;
            }
            case "access" -> access(player, args);
            default -> usage(sender);
        };
    }

    private boolean access(Player player, String[] args) {
        if (args.length < 4) return usage(player);
        Long computerId = number(args[2]);
        if (computerId == null) return usage(player);
        String action = args[3].toLowerCase(Locale.ROOT);
        if (action.equals("public") || action.equals("private")) {
            SecurityComputer current = registry.computer(computerId).orElse(null);
            if (current == null) {
                messages.send(player, "security.computer-not-found");
                return true;
            }
            boolean desired = action.equals("public");
            if (current.publicAccess() == desired) {
                messages.send(player, desired ? "security.access-public" : "security.access-private");
                return true;
            }
            async(player, () -> security.togglePublic(player.getUniqueId(), computerId), operation -> {
                result(player, operation.result());
                operation.value().ifPresent(registry::upsert);
            });
            return true;
        }
        if (!(action.equals("add") || action.equals("remove")) || args.length != 5) return usage(player);
        async(player, () -> {
            Optional<CitizenProfile> target = citizens.findByName(args[4]);
            if (target.isEmpty()) return new AccessResult(SecurityResult.CITIZEN_NOT_FOUND, null);
            SecurityResult value = action.equals("add")
                    ? security.grantAccess(player.getUniqueId(), computerId,
                            target.get().uuid(), System.currentTimeMillis())
                    : security.revokeAccess(player.getUniqueId(), computerId, target.get().uuid());
            return new AccessResult(value, target.get().lastName());
        }, outcome -> {
            if (outcome.result() == SecurityResult.SUCCESS) {
                messages.send(player, "security.access-updated",
                        Placeholder.unparsed("player", outcome.playerName()));
            } else result(player, outcome.result());
        });
        return true;
    }

    private boolean view(CommandSender sender, String[] args) {
        Player player = player(sender);
        if (player == null || args.length != 2 || args[1].isBlank()) return usage(sender);
        List<SecurityCamera> owned = registry.cameras().stream()
                .filter(camera -> camera.ownerId().equals(player.getUniqueId())).toList();
        SecurityCamera selected = owned.stream().filter(camera -> camera.name().equalsIgnoreCase(args[1]))
                .findFirst().orElse(null);
        if (selected == null) {
            messages.send(player, "security.camera-not-found");
        } else if (!views.view(player, owned, selected.id())) {
            messages.send(player, "security.view-unavailable");
        }
        return true;
    }

    private boolean switchView(CommandSender sender, int direction) {
        Player player = player(sender);
        if (player != null && !views.switchCamera(player, direction)) {
            messages.send(player, "security.switch-unavailable");
        }
        return true;
    }

    private boolean exit(CommandSender sender) {
        Player player = player(sender);
        if (player != null && !views.exit(player)) messages.send(player, "security.not-viewing");
        return true;
    }

    private boolean give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("opencivitas.security.give") || args.length < 2 || args.length > 3) {
            if (!sender.hasPermission("opencivitas.security.give")) messages.send(sender, "error.no-permission");
            else usage(sender);
            return true;
        }
        Player target = args.length == 3 ? Bukkit.getPlayerExact(args[2])
                : sender instanceof Player player ? player : null;
        if (target == null) {
            messages.send(sender, "security.player-offline");
            return true;
        }
        ItemStack item = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "camera" -> items.camera(messages.locale(target));
            case "computer" -> items.computer(messages.locale(target));
            default -> null;
        };
        if (item == null) return usage(sender);
        var overflow = target.getInventory().addItem(item);
        for (ItemStack stack : overflow.values()) target.getWorld().dropItemNaturally(target.getLocation(), stack);
        messages.send(sender, "security.item-given", Placeholder.unparsed("player", target.getName()));
        return true;
    }

    private <T> void async(Player player, Callable<T> task, Consumer<T> success) {
        database.submit(task).whenComplete((value, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled() || !player.isOnline()) return;
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Could not complete CCTV command", error);
                messages.send(player, "error.database");
            } else success.accept(value);
        }));
    }

    private void result(Player player, SecurityResult result) {
        messages.send(player, "security.result." + result.name().toLowerCase(Locale.ROOT));
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player player) return player;
        messages.send(sender, "error.player-only");
        return null;
    }

    private boolean usage(CommandSender sender) {
        messages.send(sender, "security.usage");
        return true;
    }

    private static Long number(String value) {
        try {
            long number = Long.parseLong(value);
            return number > 0 ? number : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return match(args[0], List.of(
                "group", "camera", "computer", "view", "next", "previous", "exit", "give"));
        if (args.length == 2 && args[0].equalsIgnoreCase("group")) return match(args[1], List.of(
                "list", "create", "delete", "addcamera", "removecamera"));
        if (args.length == 2 && args[0].equalsIgnoreCase("camera")) {
            return match(args[1], List.of("list", "rename", "view"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("computer")) {
            return match(args[1], List.of("list", "open", "access"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return match(args[1], List.of("camera", "computer"));
        }
        if (sender instanceof Player player && ((args.length == 2 && args[0].equalsIgnoreCase("view"))
                || (args.length == 3 && args[0].equalsIgnoreCase("camera")))) {
            return match(args[args.length - 1], registry.cameras().stream()
                    .filter(camera -> camera.ownerId().equals(player.getUniqueId()))
                    .map(SecurityCamera::name).toList());
        }
        return List.of();
    }

    private static List<String> match(String prefix, List<String> values) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }

    private record AccessResult(SecurityResult result, String playerName) {
    }
}
