package dev.opencivitas.command;

import dev.opencivitas.citizen.CitizenProfile;
import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.message.MessageService;
import dev.opencivitas.protection.PasswordHasher;
import dev.opencivitas.protection.ProtectionAccess;
import dev.opencivitas.protection.ProtectionAction;
import dev.opencivitas.protection.ProtectionGroup;
import dev.opencivitas.protection.ProtectionListener;
import dev.opencivitas.protection.ProtectionMode;
import dev.opencivitas.protection.ProtectionOperation;
import dev.opencivitas.protection.ProtectionRegistry;
import dev.opencivitas.protection.ProtectionRepository;
import dev.opencivitas.protection.ProtectionResult;
import dev.opencivitas.protection.ProtectionSessionService;
import dev.opencivitas.protection.ProtectionSource;
import dev.opencivitas.protection.ProtectionSourceType;
import dev.opencivitas.protection.ProtectionState;
import dev.opencivitas.protection.ProtectionType;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class ProtectionCommand implements CommandExecutor, TabCompleter {
    private static final Pattern GROUP_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final Pattern PERMISSION = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
    private static final List<String> ACTIONS = List.of(
            "lock", "unlock", "edit", "modify", "group", "trust", "transfer",
            "password", "mode", "persist", "nolock", "nospam", "info", "cancel", "help");

    private final JavaPlugin plugin;
    private final Database database;
    private final CitizenRepository citizens;
    private final ProtectionRepository protections;
    private final ProtectionRegistry registry;
    private final ProtectionSessionService sessions;
    private final PasswordHasher passwords;
    private final MessageService messages;

    public ProtectionCommand(
            JavaPlugin plugin,
            Database database,
            CitizenRepository citizens,
            ProtectionRepository protections,
            ProtectionRegistry registry,
            ProtectionSessionService sessions,
            PasswordHasher passwords,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.citizens = citizens;
        this.protections = protections;
        this.registry = registry;
        this.sessions = sessions;
        this.passwords = passwords;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (label.equalsIgnoreCase("lock")) return lock(sender, prepend("lock", args));
        if (label.equalsIgnoreCase("unlock")) return arm(sender,
                ProtectionAction.simple(ProtectionAction.Kind.UNLOCK), "unlock");
        if (label.equalsIgnoreCase("cmodify")) return edit(sender, prepend("edit", args));
        if (args.length == 0) return help(sender);
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "lock" -> lock(sender, args);
            case "unlock" -> arm(sender, ProtectionAction.simple(ProtectionAction.Kind.UNLOCK), "unlock");
            case "edit" -> edit(sender, args);
            case "modify" -> modify(sender, args);
            case "group" -> group(sender, args);
            case "trust" -> trust(sender, args);
            case "transfer" -> transfer(sender, args);
            case "password" -> password(sender, args);
            case "mode" -> mode(sender, args);
            case "persist", "nolock", "nospam" -> mode(sender, new String[]{"mode", args[0]});
            case "info" -> arm(sender, ProtectionAction.simple(ProtectionAction.Kind.INFO), "info");
            case "cancel" -> cancel(sender);
            case "help" -> help(sender);
            default -> help(sender);
        };
    }

    private boolean lock(CommandSender sender, String[] args) {
        if (args.length > 2) return usage(sender, "/bolt lock [private|display|deposit|withdrawal|public]");
        ProtectionType type = args.length == 1
                ? ProtectionType.PRIVATE : ProtectionType.parse(args[1]).orElse(null);
        if (type == null) {
            messages.send(sender, "protection.invalid-type");
            return true;
        }
        return arm(sender, ProtectionAction.lock(type), "lock");
    }

    private boolean edit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 3 || !addOrRemove(args[1])) {
            return usage(sender, "/bolt edit <add|remove> <player>");
        }
        boolean adding = args[1].equalsIgnoreCase("add");
        complete(sender, database.submit(() -> citizens.findByName(args[2]).orElse(null)), target -> {
            if (target == null) {
                messages.send(sender, "protection.citizen-not-found");
                return;
            }
            sessions.setAction(player.getUniqueId(), ProtectionAction.modify(
                    adding, ProtectionAccess.NORMAL, ProtectionSourceType.PLAYER,
                    target.uuid().toString()));
            clickAction(player, adding ? "add access" : "remove access");
        });
        return true;
    }

    private boolean modify(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length < 4 || !addOrRemove(args[1])) {
            return usage(sender, "/bolt modify <add|remove> <normal|admin> <player|group|permission|password> <source>");
        }
        boolean adding = args[1].equalsIgnoreCase("add");
        if (args[2].equalsIgnoreCase("auto_close") && args[3].equalsIgnoreCase("door")) {
            if (args.length != 5 || !booleanValue(args[4])) {
                return usage(sender, "/bolt modify add auto_close door <true|false>");
            }
            boolean enabled = adding && Boolean.parseBoolean(args[4]);
            sessions.setAction(player.getUniqueId(), ProtectionAction.autoClose(enabled));
            clickAction(player, enabled ? "enable auto-close" : "disable auto-close");
            return true;
        }
        ProtectionAccess access = ProtectionAccess.parse(args[2]).orElse(null);
        if (access == null) {
            messages.send(sender, "protection.invalid-access");
            return true;
        }
        if (access == ProtectionAccess.ADMIN
                && !sender.hasPermission("opencivitas.protection.admin-access")) {
            messages.send(sender, "error.no-permission");
            return true;
        }
        ProtectionSourceType sourceType = ProtectionSourceType.parse(args[3]).orElse(null);
        if (sourceType == null) {
            messages.send(sender, "protection.invalid-source");
            return true;
        }
        if (args.length == 4 && sourceType == ProtectionSourceType.PASSWORD) {
            sessions.promptForActionSecret(player.getUniqueId(), ProtectionAction.modify(
                    adding, access, ProtectionSourceType.PASSWORD, ""));
            messages.send(sender, "protection.password-prompt");
            return true;
        }
        if (args.length != 5) {
            return usage(sender, "/bolt modify <add|remove> <normal|admin> <source-type> <source>");
        }
        String identifier = commandSecret(player, args[4]);
        resolveSource(sender, player.getUniqueId(), sourceType, identifier, source -> {
            sessions.setAction(player.getUniqueId(), ProtectionAction.modify(
                    adding, access, source.type(), source.identifier()));
            clickAction(player, adding ? "add access" : "remove access");
        });
        return true;
    }

    private boolean group(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length < 3) {
            return usage(sender, "/bolt group <create|delete|add|remove|list> <group> [players...]");
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        String name = normalizeGroup(args[2]);
        if (name == null) {
            messages.send(sender, "protection.invalid-group");
            return true;
        }
        if (action.equals("list")) return groupList(sender, player, name, args);
        if (action.equals("delete")) {
            if (args.length != 3) return usage(sender, "/bolt group delete <group>");
            complete(sender, database.submit(() -> {
                ProtectionOperation<ProtectionGroup> operation =
                        protections.deleteGroup(player.getUniqueId(), name);
                return new GroupDeletion(operation,
                        operation.result() == ProtectionResult.SUCCESS ? protections.loadState() : null);
            }), deletion -> {
                if (deletion.state() != null) registry.replaceAll(deletion.state());
                result(sender, deletion.operation().result(), "protection.group-deleted");
            });
            return true;
        }
        if (!Set.of("create", "add", "remove").contains(action)) {
            return usage(sender, "/bolt group <create|delete|add|remove|list> <group> [players...]");
        }
        if (!action.equals("create") && args.length < 4) {
            return usage(sender, "/bolt group <add|remove> <group> <players...>");
        }
        List<String> memberNames = args.length <= 3
                ? List.of() : List.of(Arrays.copyOfRange(args, 3, args.length));
        complete(sender, database.submit(() -> modifyGroup(
                player.getUniqueId(), name, action, memberNames)), operation -> {
            operation.value().ifPresent(registry::upsert);
            String success = switch (action) {
                case "create" -> "protection.group-created";
                case "add" -> "protection.group-members-added";
                default -> "protection.group-members-removed";
            };
            result(sender, operation.result(), success);
        });
        return true;
    }

    private ProtectionOperation<ProtectionGroup> modifyGroup(
            UUID ownerId,
            String name,
            String action,
            List<String> memberNames
    ) throws Exception {
        long now = Instant.now().toEpochMilli();
        ProtectionOperation<ProtectionGroup> operation = action.equals("create")
                ? protections.createGroup(ownerId, name, now)
                : registry.group(ownerId, name)
                        .map(ProtectionOperation::success)
                        .orElseGet(() -> ProtectionOperation.failed(ProtectionResult.GROUP_NOT_FOUND));
        if (operation.result() != ProtectionResult.SUCCESS) return operation;
        for (String memberName : memberNames) {
            CitizenProfile member = citizens.findByName(memberName).orElse(null);
            if (member == null) return new ProtectionOperation<>(
                    ProtectionResult.CITIZEN_NOT_FOUND, operation.value());
            ProtectionOperation<ProtectionGroup> next = protections.modifyGroup(
                    ownerId, name, member.uuid(), !action.equals("remove"), now);
            if (next.result() != ProtectionResult.SUCCESS) {
                return new ProtectionOperation<>(next.result(), operation.value());
            }
            operation = next;
        }
        return operation;
    }

    private boolean groupList(CommandSender sender, Player player, String name, String[] args) {
        if (args.length != 3) return usage(sender, "/bolt group list <group>");
        complete(sender, database.submit(() -> {
            ProtectionGroup group = registry.group(player.getUniqueId(), name).orElse(null);
            if (group == null) return null;
            List<String> names = new ArrayList<>();
            for (UUID memberId : group.members()) {
                names.add(citizens.find(memberId).map(CitizenProfile::lastName).orElse(memberId.toString()));
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return new GroupView(group.name(), names);
        }), view -> {
            if (view == null) {
                messages.send(sender, "protection.result.group_not_found");
            } else {
                messages.send(sender, "protection.group-list",
                        Placeholder.unparsed("group", view.name()),
                        Placeholder.unparsed("members", view.members().isEmpty()
                                ? "none" : String.join(", ", view.members())));
            }
        });
        return true;
    }

    private boolean trust(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length == 1 || args.length == 2 && args[1].equalsIgnoreCase("list")) {
            return trustList(sender, player, args);
        }
        if (args.length < 3 || !Set.of("add", "remove", "confirm")
                .contains(args[1].toLowerCase(Locale.ROOT))) {
            return usage(sender, "/bolt trust <list|add|remove|confirm> [source-type] <source> [normal|admin]");
        }
        boolean adding = !args[1].equalsIgnoreCase("remove");
        ProtectionSourceType sourceType;
        String identifier;
        ProtectionAccess access = ProtectionAccess.NORMAL;
        if (args.length == 3) {
            sourceType = ProtectionSourceType.PLAYER;
            identifier = args[2];
        } else {
            sourceType = ProtectionSourceType.parse(args[2]).orElse(null);
            identifier = commandSecret(player, args[3]);
            if (args.length >= 5) access = ProtectionAccess.parse(args[4]).orElse(null);
        }
        if (sourceType == null) {
            messages.send(sender, "protection.invalid-source");
            return true;
        }
        if (access == null) {
            messages.send(sender, "protection.invalid-access");
            return true;
        }
        if (access == ProtectionAccess.ADMIN
                && !sender.hasPermission("opencivitas.protection.admin-access")) {
            messages.send(sender, "error.no-permission");
            return true;
        }
        ProtectionAccess selectedAccess = access;
        resolveSource(sender, player.getUniqueId(), sourceType, identifier, source ->
                complete(sender, database.submit(() -> modifyTrust(
                        player.getUniqueId(), source, selectedAccess, adding)), operation -> {
                    operation.value().ifPresent(values -> registry.setTrust(player.getUniqueId(), values));
                    result(sender, operation.result(), adding
                            ? "protection.trust-added" : "protection.trust-removed");
                }));
        return true;
    }

    private ProtectionOperation<Map<ProtectionSource, ProtectionAccess>> modifyTrust(
            UUID ownerId,
            ProtectionSource source,
            ProtectionAccess access,
            boolean adding
    ) throws Exception {
        ProtectionSource resolved = source;
        if (source.type() == ProtectionSourceType.PASSWORD) {
            if (adding) {
                boolean exists = registry.trust(ownerId).keySet().stream()
                        .filter(candidate -> candidate.type() == ProtectionSourceType.PASSWORD)
                        .anyMatch(candidate -> passwords.matches(
                                source.identifier(), candidate.identifier()));
                if (exists) return ProtectionOperation.failed(ProtectionResult.SOURCE_EXISTS);
                resolved = new ProtectionSource(ProtectionSourceType.PASSWORD,
                        passwords.hash(source.identifier()));
            } else {
                resolved = registry.trust(ownerId).keySet().stream()
                        .filter(candidate -> candidate.type() == ProtectionSourceType.PASSWORD)
                        .filter(candidate -> passwords.matches(source.identifier(), candidate.identifier()))
                        .findFirst().orElse(null);
                if (resolved == null) return ProtectionOperation.failed(ProtectionResult.SOURCE_NOT_FOUND);
            }
        }
        return protections.modifyTrust(
                ownerId, resolved, access, adding, Instant.now().toEpochMilli());
    }

    private boolean trustList(CommandSender sender, Player player, String[] args) {
        if (args.length > 2) return usage(sender, "/bolt trust [list]");
        complete(sender, database.submit(() -> {
            List<String> values = new ArrayList<>();
            for (Map.Entry<ProtectionSource, ProtectionAccess> entry
                    : registry.trust(player.getUniqueId()).entrySet()) {
                ProtectionSource source = entry.getKey();
                String identifier = source.identifier();
                if (source.type() == ProtectionSourceType.PLAYER) {
                    UUID playerId = UUID.fromString(identifier);
                    identifier = citizens.find(playerId)
                            .map(CitizenProfile::lastName).orElse(playerId.toString());
                } else if (source.type() == ProtectionSourceType.PASSWORD) {
                    identifier = "<password>";
                }
                values.add(source.type().name().toLowerCase(Locale.ROOT)
                        + ":" + identifier + " (" + entry.getValue().name().toLowerCase(Locale.ROOT) + ")");
            }
            values.sort(String.CASE_INSENSITIVE_ORDER);
            return values;
        }), values -> messages.send(sender, "protection.trust-list",
                Placeholder.unparsed("sources", values.isEmpty() ? "none" : String.join(", ", values))));
        return true;
    }

    private boolean transfer(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 2) return usage(sender, "/bolt transfer <player>");
        complete(sender, database.submit(() -> citizens.findByName(args[1]).orElse(null)), target -> {
            if (target == null) {
                messages.send(sender, "protection.citizen-not-found");
                return;
            }
            sessions.setAction(player.getUniqueId(), ProtectionAction.transfer(target.uuid()));
            clickAction(player, "transfer ownership to " + target.lastName());
        });
        return true;
    }

    private boolean password(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length == 1) {
            sessions.promptForPassword(player.getUniqueId());
            messages.send(sender, "protection.password-prompt");
            return true;
        }
        if (args.length != 2 || args[1].isEmpty() || args[1].length() > 128) {
            return usage(sender, "/bolt password <password>");
        }
        String secret = commandSecret(player, args[1]);
        if (secret.isEmpty()) {
            messages.send(sender, "protection.invalid-source");
            return true;
        }
        complete(sender, database.submit(() -> {
            Set<String> matched = new LinkedHashSet<>();
            for (String hash : registry.passwordHashes(passwords.fingerprint(secret))) {
                if (passwords.matches(secret, hash)) matched.add(hash);
            }
            return Set.copyOf(matched);
        }), matched -> {
            sessions.addPasswordAuthorizations(player.getUniqueId(), matched);
            messages.send(sender, matched.isEmpty()
                    ? "protection.password-invalid" : "protection.password-accepted");
        });
        return true;
    }

    private boolean mode(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 2) return usage(sender, "/bolt mode <persist|nolock|nospam>");
        ProtectionMode mode;
        try {
            mode = ProtectionMode.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return usage(sender, "/bolt mode <persist|nolock|nospam>");
        }
        boolean enabled = sessions.toggleMode(player.getUniqueId(), mode);
        messages.send(sender, "protection.mode",
                Placeholder.unparsed("mode", mode.name().toLowerCase(Locale.ROOT)),
                Placeholder.unparsed("state", enabled ? "on" : "off"));
        return true;
    }

    private boolean cancel(CommandSender sender) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        sessions.cancelPending(player.getUniqueId());
        messages.send(sender, "protection.action-cancelled");
        return true;
    }

    private boolean help(CommandSender sender) {
        messages.send(sender, "protection.help");
        return true;
    }

    private boolean arm(CommandSender sender, ProtectionAction action, String description) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        sessions.setAction(player.getUniqueId(), action);
        clickAction(player, description);
        return true;
    }

    private void clickAction(Player player, String action) {
        messages.send(player, "protection.click-action", Placeholder.unparsed("action", action));
    }

    private void resolveSource(
            CommandSender sender,
            UUID ownerId,
            ProtectionSourceType type,
            String rawIdentifier,
            Consumer<ProtectionSource> resolved
    ) {
        switch (type) {
            case PLAYER -> complete(sender,
                    database.submit(() -> citizens.findByName(rawIdentifier).orElse(null)), target -> {
                        if (target == null) messages.send(sender, "protection.citizen-not-found");
                        else resolved.accept(new ProtectionSource(type, target.uuid().toString()));
                    });
            case GROUP -> {
                String group = normalizeGroup(rawIdentifier);
                if (group == null || registry.group(ownerId, group).isEmpty()) {
                    messages.send(sender, "protection.result.group_not_found");
                } else {
                    resolved.accept(new ProtectionSource(type, group));
                }
            }
            case PERMISSION -> {
                String permission = rawIdentifier.toLowerCase(Locale.ROOT);
                if (!sender.hasPermission("opencivitas.protection.permission-source")) {
                    messages.send(sender, "error.no-permission");
                } else if (!PERMISSION.matcher(permission).matches()) {
                    messages.send(sender, "protection.invalid-source");
                } else {
                    resolved.accept(new ProtectionSource(type, permission));
                }
            }
            case PASSWORD -> {
                if (rawIdentifier.isEmpty() || rawIdentifier.length() > 128) {
                    messages.send(sender, "protection.invalid-source");
                } else {
                    resolved.accept(new ProtectionSource(type, rawIdentifier));
                }
            }
        }
    }

    private void result(CommandSender sender, ProtectionResult result, String successKey) {
        messages.send(sender, result == ProtectionResult.SUCCESS
                ? successKey : "protection.result." + result.name().toLowerCase(Locale.ROOT));
    }

    private <T> void complete(CommandSender sender, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((value, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "An asynchronous block protection command failed", error);
                messages.send(sender, "error.database");
            } else {
                success.accept(value);
            }
        }));
    }

    private boolean usage(CommandSender sender, String usage) {
        sender.sendMessage(messages.parse("<yellow>" + usage + "</yellow>"));
        return true;
    }

    private boolean playerOnly(CommandSender sender) {
        messages.send(sender, "error.player-only");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return prefix(ACTIONS, args[0]);
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (action) {
                case "lock" -> prefix(Arrays.stream(ProtectionType.values())
                        .map(type -> type.name().toLowerCase(Locale.ROOT)).toList(), args[1]);
                case "edit", "modify", "group", "trust" -> prefix(
                        action.equals("group") ? List.of("create", "delete", "add", "remove", "list")
                                : action.equals("trust") ? List.of("list", "add", "remove", "confirm")
                                : List.of("add", "remove"), args[1]);
                case "transfer" -> onlinePlayers(args[1]);
                case "mode" -> prefix(List.of("persist", "nolock", "nospam"), args[1]);
                default -> List.of();
            };
        }
        if (action.equals("edit") && args.length == 3) return onlinePlayers(args[2]);
        if (action.equals("modify") && args.length == 3) {
            return prefix(List.of("normal", "admin", "auto_close"), args[2]);
        }
        if (action.equals("modify") && args.length == 4) {
            return prefix(args[2].equalsIgnoreCase("auto_close")
                    ? List.of("door") : List.of("player", "group", "permission", "password"), args[3]);
        }
        if (action.equals("group") && args.length == 3 && sender instanceof Player player) {
            return prefix(new ArrayList<>(registry.groupNames(player.getUniqueId())), args[2]);
        }
        if (action.equals("group") && args.length >= 4) return onlinePlayers(args[args.length - 1]);
        return List.of();
    }

    private static List<String> prefix(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }

    private static List<String> onlinePlayers(String prefix) {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }

    private static String normalizeGroup(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return GROUP_NAME.matcher(normalized).matches() ? normalized : null;
    }

    private static boolean addOrRemove(String value) {
        return value.equalsIgnoreCase("add") || value.equalsIgnoreCase("remove");
    }

    private static boolean booleanValue(String value) {
        return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
    }

    private String commandSecret(Player player, String value) {
        if (!value.equals(ProtectionListener.REDACTED_SECRET)) return value;
        return sessions.consumeCommandSecret(player.getUniqueId()).orElse("");
    }

    private static String[] prepend(String value, String[] args) {
        String[] result = new String[args.length + 1];
        result[0] = value;
        System.arraycopy(args, 0, result, 1, args.length);
        return result;
    }

    private record GroupView(String name, List<String> members) {
    }

    private record GroupDeletion(
            ProtectionOperation<ProtectionGroup> operation,
            ProtectionState state
    ) {
    }
}
