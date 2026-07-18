package dev.opencivitas.command;

import dev.opencivitas.citizen.CitizenProfile;
import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.family.FamilyOperation;
import dev.opencivitas.family.FamilyPolicy;
import dev.opencivitas.family.FamilyRegistry;
import dev.opencivitas.family.FamilyRepository;
import dev.opencivitas.family.FamilyResult;
import dev.opencivitas.family.Friendship;
import dev.opencivitas.family.Marriage;
import dev.opencivitas.family.PartnerState;
import dev.opencivitas.message.MessageService;
import dev.opencivitas.navigation.SafeTeleportService;
import dev.opencivitas.navigation.SavedLocation;
import net.kyori.adventure.text.Component;
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

public final class FamilyCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final Database database;
    private final CitizenRepository citizens;
    private final FamilyRepository families;
    private final FamilyRegistry registry;
    private final FamilyPolicy policy;
    private final SafeTeleportService teleports;
    private final MessageService messages;

    public FamilyCommand(
            JavaPlugin plugin,
            Database database,
            CitizenRepository citizens,
            FamilyRepository families,
            FamilyRegistry registry,
            FamilyPolicy policy,
            SafeTeleportService teleports,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.citizens = citizens;
        this.families = families;
        this.registry = registry;
        this.policy = policy;
        this.teleports = teleports;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "friend" -> friend(sender, args);
            case "marriage" -> marriage(sender, args);
            case "partnerchat" -> partnerChat(sender, args);
            case "partnerhome" -> partnerHome(sender, args);
            case "setpartnerhome" -> setPartnerHome(sender, args);
            case "partnerpvp" -> partnerPvp(sender, args);
            default -> false;
        };
    }

    private boolean friend(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) return listFriends(sender, player, args);
        if (args.length != 2) return usage(sender, "/friend <add|accept|decline|remove|list> [player]");
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!List.of("add", "accept", "decline", "remove").contains(action)) {
            return usage(sender, "/friend <add|accept|decline|remove|list> [player]");
        }
        complete(sender, database.submit(() -> {
            CitizenProfile target = citizens.findByName(args[1]).orElse(null);
            if (target == null) return new TargetResult(FamilyResult.CITIZEN_NOT_FOUND, null);
            FamilyResult result = switch (action) {
                case "add" -> families.requestFriend(player.getUniqueId(), target.uuid(),
                        policy.friendRequestExpiry(), System.currentTimeMillis());
                case "accept" -> families.respondFriend(
                        player.getUniqueId(), target.uuid(), true, System.currentTimeMillis());
                case "decline" -> families.respondFriend(
                        player.getUniqueId(), target.uuid(), false, System.currentTimeMillis());
                case "remove" -> families.removeFriend(player.getUniqueId(), target.uuid());
                default -> throw new IllegalStateException();
            };
            return new TargetResult(result, target);
        }), operation -> {
            if (operation.result() != FamilyResult.SUCCESS) {
                error(sender, operation.result());
                return;
            }
            String key = switch (action) {
                case "add" -> "family.friend-requested";
                case "accept" -> "family.friend-accepted";
                case "decline" -> "family.friend-declined";
                case "remove" -> "family.friend-removed";
                default -> throw new IllegalStateException();
            };
            messages.send(sender, key, Placeholder.unparsed("player", operation.target().lastName()));
            Player online = Bukkit.getPlayer(operation.target().uuid());
            if (online != null) notifyFriendTarget(online, action, player.getName());
        });
        return true;
    }

    private boolean listFriends(CommandSender sender, Player player, String[] args) {
        if (args.length > 1 || args.length == 1 && !args[0].equalsIgnoreCase("list")) {
            return usage(sender, "/friend list");
        }
        complete(sender, database.submit(() -> families.friends(player.getUniqueId())), friends -> {
            messages.send(sender, "family.friends-header");
            if (friends.isEmpty()) messages.send(sender, "family.friends-empty");
            for (Friendship friendship : friends) messages.send(sender, "family.friend-entry",
                    Placeholder.unparsed("player", friendship.friendName()));
        });
        return true;
    }

    private boolean marriage(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) return marriageInfo(sender, player, args);
        String action = args[0].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "propose" -> marriageProposal(sender, player, args, "propose");
            case "accept" -> marriageProposal(sender, player, args, "accept");
            case "decline" -> marriageProposal(sender, player, args, "decline");
            case "officiate" -> officiate(sender, player, args);
            case "divorce" -> divorce(sender, player, args);
            case "sethome" -> setPartnerHome(sender, Arrays.copyOfRange(args, 1, args.length));
            case "home" -> partnerHome(sender, Arrays.copyOfRange(args, 1, args.length));
            case "pvp" -> partnerPvp(sender, Arrays.copyOfRange(args, 1, args.length));
            case "chat" -> partnerChat(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> usage(sender,
                    "/marriage <info|propose|accept|decline|officiate|divorce|sethome|home|pvp|chat>");
        };
    }

    private boolean marriageInfo(CommandSender sender, Player player, String[] args) {
        if (args.length > 1) return usage(sender, "/marriage info");
        PartnerState partner = registry.partner(player.getUniqueId()).orElse(null);
        if (partner == null) {
            messages.send(sender, "family.not-married");
            return true;
        }
        messages.send(sender, "family.marriage-info",
                Placeholder.unparsed("partner", partner.partnerName()),
                Placeholder.component("pvp", messages.component(sender,
                        partner.pvpAllowed() ? "family.enabled" : "family.disabled")),
                Placeholder.component("home", messages.component(sender,
                        partner.home() == null ? "family.not-set" : "family.set")));
        return true;
    }

    private boolean marriageProposal(CommandSender sender, Player player, String[] args, String action) {
        if (args.length != 2) return usage(sender, "/marriage " + action + " <player>");
        complete(sender, database.submit(() -> {
            CitizenProfile target = citizens.findByName(args[1]).orElse(null);
            if (target == null) return new TargetResult(FamilyResult.CITIZEN_NOT_FOUND, null);
            FamilyResult result = switch (action) {
                case "propose" -> families.proposeMarriage(player.getUniqueId(), target.uuid(),
                        policy.marriageProposalExpiry(), System.currentTimeMillis());
                case "accept" -> families.respondMarriage(
                        player.getUniqueId(), target.uuid(), true, System.currentTimeMillis());
                case "decline" -> families.respondMarriage(
                        player.getUniqueId(), target.uuid(), false, System.currentTimeMillis());
                default -> throw new IllegalStateException();
            };
            return new TargetResult(result, target);
        }), operation -> {
            if (operation.result() != FamilyResult.SUCCESS) {
                error(sender, operation.result());
                return;
            }
            String confirmationKey = switch (action) {
                case "propose" -> "family.marriage-proposed";
                case "accept" -> "family.marriage-accepted";
                case "decline" -> "family.marriage-declined";
                default -> throw new IllegalStateException();
            };
            messages.send(sender, confirmationKey,
                    Placeholder.unparsed("player", operation.target().lastName()));
            Player online = Bukkit.getPlayer(operation.target().uuid());
            if (online != null) messages.send(online, "family.marriage-" + action + "-notice",
                    Placeholder.unparsed("player", player.getName()));
        });
        return true;
    }

    private boolean officiate(CommandSender sender, Player lawyer, String[] args) {
        if (args.length != 3) return usage(sender, "/marriage officiate <player1> <player2>");
        complete(sender, database.submit(() -> {
            CitizenProfile first = citizens.findByName(args[1]).orElse(null);
            CitizenProfile second = citizens.findByName(args[2]).orElse(null);
            if (first == null || second == null) return new OfficiateResult(
                    FamilyOperation.result(FamilyResult.CITIZEN_NOT_FOUND), null, null);
            return new OfficiateResult(families.officiate(lawyer.getUniqueId(), first.uuid(), second.uuid(),
                    policy, System.currentTimeMillis()), first, second);
        }), result -> {
            if (result.operation().result() != FamilyResult.SUCCESS) {
                error(sender, result.operation().result());
                return;
            }
            Marriage marriage = result.operation().value().orElseThrow();
            registry.upsert(marriage);
            messages.send(sender, "family.marriage-officiated",
                    Placeholder.unparsed("first", result.first().lastName()),
                    Placeholder.unparsed("second", result.second().lastName()));
            notifySpouse(result.first(), "family.married-notice", result.second().lastName(), lawyer.getName());
            notifySpouse(result.second(), "family.married-notice", result.first().lastName(), lawyer.getName());
        });
        return true;
    }

    private boolean divorce(CommandSender sender, Player player, String[] args) {
        if (args.length < 2) return usage(sender, "/marriage divorce <reason...>");
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        complete(sender, database.submit(() -> families.divorce(
                player.getUniqueId(), reason, System.currentTimeMillis())), operation -> {
            if (operation.result() != FamilyResult.SUCCESS) {
                error(sender, operation.result());
                return;
            }
            Marriage marriage = operation.value().orElseThrow();
            registry.remove(marriage);
            String partnerName = marriage.partnerName(player.getUniqueId());
            messages.send(sender, "family.divorced", Placeholder.unparsed("player", partnerName));
            Player partner = Bukkit.getPlayer(marriage.partnerId(player.getUniqueId()));
            if (partner != null) messages.send(partner, "family.divorce-notice",
                    Placeholder.unparsed("player", player.getName()),
                    Placeholder.unparsed("reason", reason));
        });
        return true;
    }

    private boolean setPartnerHome(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 0) return usage(sender, "/setpartnerhome");
        Location location = player.getLocation();
        SavedLocation home = new SavedLocation("partner-home", location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        complete(sender, database.submit(() -> families.setHome(
                player.getUniqueId(), home, System.currentTimeMillis())), operation -> {
            if (operation.result() != FamilyResult.SUCCESS) error(sender, operation.result());
            else {
                registry.upsert(operation.value().orElseThrow());
                messages.send(sender, "family.partner-home-set");
            }
        });
        return true;
    }

    private boolean partnerHome(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 0) return usage(sender, "/partnerhome");
        PartnerState partner = registry.partner(player.getUniqueId()).orElse(null);
        if (partner == null) return errorTrue(sender, FamilyResult.NOT_MARRIED);
        if (partner.home() == null) return errorTrue(sender, FamilyResult.HOME_NOT_SET);
        teleports.teleport(player, partner.home(), outcome -> messages.send(sender, switch (outcome) {
            case SUCCESS -> "family.partner-home-arrived";
            case WORLD_UNAVAILABLE -> "navigation.world-unavailable";
            case UNSAFE_DESTINATION -> "navigation.unsafe-destination";
            case FAILED -> "navigation.teleport-failed";
        }));
        return true;
    }

    private boolean partnerPvp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 1 || !(args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))) {
            return usage(sender, "/partnerpvp <on|off>");
        }
        boolean enabled = args[0].equalsIgnoreCase("on");
        complete(sender, database.submit(() -> families.setPartnerPvp(
                player.getUniqueId(), enabled, System.currentTimeMillis())), operation -> {
            if (operation.result() != FamilyResult.SUCCESS) error(sender, operation.result());
            else {
                registry.upsert(operation.value().orElseThrow());
                messages.send(sender, enabled ? "family.partner-pvp-enabled" : "family.partner-pvp-disabled");
            }
        });
        return true;
    }

    private boolean partnerChat(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length == 0) return usage(sender, "/partnerchat <message>");
        String content = String.join(" ", args).trim();
        if (content.isEmpty() || content.length() > 500) return errorTrue(sender, FamilyResult.INVALID_CONTENT);
        PartnerState relationship = registry.partner(player.getUniqueId()).orElse(null);
        if (relationship == null) return errorTrue(sender, FamilyResult.NOT_MARRIED);
        Player partner = Bukkit.getPlayer(relationship.partnerId());
        if (partner == null) {
            messages.send(sender, "family.partner-offline");
            return true;
        }
        Component message = Component.text(content);
        messages.send(sender, "family.partner-chat",
                Placeholder.unparsed("player", player.getName()), Placeholder.component("message", message));
        messages.send(partner, "family.partner-chat",
                Placeholder.unparsed("player", player.getName()), Placeholder.component("message", message));
        return true;
    }

    private void notifyFriendTarget(Player target, String action, String actor) {
        String key = switch (action) {
            case "add" -> "family.friend-request-notice";
            case "accept" -> "family.friend-accepted-notice";
            case "decline" -> "family.friend-declined-notice";
            case "remove" -> "family.friend-removed-notice";
            default -> throw new IllegalStateException();
        };
        messages.send(target, key, Placeholder.unparsed("player", actor));
    }

    private void notifySpouse(CitizenProfile spouse, String key, String partner, String lawyer) {
        Player online = Bukkit.getPlayer(spouse.uuid());
        if (online != null) messages.send(online, key,
                Placeholder.unparsed("partner", partner), Placeholder.unparsed("lawyer", lawyer));
    }

    private void error(CommandSender sender, FamilyResult result) {
        messages.send(sender, switch (result) {
            case CITIZEN_NOT_FOUND -> "error.player-not-found";
            case CANNOT_TARGET_SELF -> "family.cannot-target-self";
            case ALREADY_FRIENDS -> "family.already-friends";
            case REQUEST_ALREADY_PENDING -> "family.friend-request-pending";
            case REQUEST_NOT_FOUND -> "family.friend-request-not-found";
            case NOT_FRIENDS -> "family.not-friends";
            case ALREADY_MARRIED -> "family.already-married";
            case PROPOSAL_ALREADY_PENDING -> "family.marriage-proposal-pending";
            case PROPOSAL_NOT_FOUND -> "family.marriage-proposal-not-found";
            case PROPOSAL_NOT_ACCEPTED -> "family.marriage-proposal-not-accepted";
            case NOT_LAWYER -> "family.not-lawyer";
            case NOT_MARRIED, NOT_SPOUSE -> "family.not-married";
            case HOME_NOT_SET -> "family.partner-home-not-set";
            case INVALID_CONTENT -> "family.invalid-content";
            case SUCCESS -> "family.operation-complete";
        });
    }

    private boolean errorTrue(CommandSender sender, FamilyResult result) {
        error(sender, result);
        return true;
    }

    private <T> void complete(CommandSender sender, CompletableFuture<T> future,
                              java.util.function.Consumer<T> success) {
        future.whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                plugin.getLogger().log(Level.SEVERE, "Family operation failed", cause);
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("friend")) {
            if (args.length == 1) return filter(List.of("add", "accept", "decline", "remove", "list"), args[0]);
            if (args.length == 2) return onlineNames(args[1]);
        }
        if (name.equals("marriage")) {
            if (args.length == 1) return filter(List.of(
                    "info", "propose", "accept", "decline", "officiate", "divorce",
                    "sethome", "home", "pvp", "chat"), args[0]);
            if (args.length == 2 && List.of("propose", "accept", "decline", "officiate").contains(
                    args[0].toLowerCase(Locale.ROOT))) return onlineNames(args[1]);
            if (args.length == 3 && args[0].equalsIgnoreCase("officiate")) return onlineNames(args[2]);
        }
        if (name.equals("partnerpvp") && args.length == 1) return filter(List.of("on", "off"), args[0]);
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

    private record TargetResult(FamilyResult result, CitizenProfile target) {
    }

    private record OfficiateResult(
            FamilyOperation<Marriage> operation, CitizenProfile first, CitizenProfile second) {
    }
}
