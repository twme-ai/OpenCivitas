package dev.opencivitas.command;

import dev.opencivitas.chat.ChatChannel;
import dev.opencivitas.chat.ChatOperation;
import dev.opencivitas.chat.ChatPolicy;
import dev.opencivitas.chat.ChatRepository;
import dev.opencivitas.chat.ChatResult;
import dev.opencivitas.chat.ChatRouter;
import dev.opencivitas.chat.DepartmentChannelDefinition;
import dev.opencivitas.chat.IgnoredPlayer;
import dev.opencivitas.chat.MailMessage;
import dev.opencivitas.citizen.CitizenProfile;
import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.message.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

public final class ChatCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss z");

    private final JavaPlugin plugin;
    private final Database database;
    private final CitizenRepository citizens;
    private final ChatRepository chat;
    private final ChatPolicy policy;
    private final ChatRouter router;
    private final MessageService messages;

    public ChatCommand(
            JavaPlugin plugin,
            Database database,
            CitizenRepository citizens,
            ChatRepository chat,
            ChatPolicy policy,
            ChatRouter router,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.citizens = citizens;
        this.chat = chat;
        this.policy = policy;
        this.router = router;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "g" -> select(sender, args, ChatChannel.GLOBAL);
            case "l" -> select(sender, args, ChatChannel.LOCAL);
            case "murmur" -> select(sender, args, ChatChannel.MURMUR);
            case "doj" -> selectDepartment(sender, args, ChatChannel.DOJ);
            case "sen" -> selectDepartment(sender, args, ChatChannel.SENATE);
            case "jud" -> selectDepartment(sender, args, ChatChannel.JUDICIARY);
            case "msg" -> message(sender, args);
            case "r" -> reply(sender, args);
            case "mail" -> mail(sender, args);
            case "ad" -> advertise(sender, args);
            case "ask" -> ask(sender, args);
            case "ignore" -> ignore(sender, args);
            case "unignore" -> unignore(sender, args);
            case "timestamp" -> timestamp(sender, args);
            default -> false;
        };
    }

    private boolean select(CommandSender sender, String[] args, ChatChannel channel) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 0) return usage(sender, "/" + channel.name().toLowerCase(Locale.ROOT));
        router.select(player, channel);
        return true;
    }

    private boolean selectDepartment(CommandSender sender, String[] args, ChatChannel channel) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 0) return usage(sender, "/" + departmentCommand(channel));
        DepartmentChannelDefinition department = policy.department(channel).orElseThrow();
        complete(sender, database.submit(() -> chat.isDepartmentMember(
                player.getUniqueId(), department, System.currentTimeMillis())), authorized -> {
            if (!authorized) messages.send(sender, "chat.department-denied");
            else router.select(player, channel);
        });
        return true;
    }

    private boolean message(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length < 2) return usage(sender, "/msg <player> <message>");
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            messages.send(sender, "chat.target-offline");
            return true;
        }
        String content = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        return privateMessage(player, target, content);
    }

    private boolean reply(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length < 1) return usage(sender, "/r <message>");
        String content = String.join(" ", args);
        complete(sender, database.submit(() -> chat.replyTarget(player.getUniqueId())), targetId -> {
            if (targetId.isEmpty()) {
                messages.send(sender, "chat.no-reply-target");
                return;
            }
            Player target = Bukkit.getPlayer(targetId.orElseThrow());
            if (target == null) {
                messages.send(sender, "chat.target-offline");
                return;
            }
            privateMessage(player, target, content);
        });
        return true;
    }

    private boolean privateMessage(Player sender, Player target, String content) {
        if (sender.getUniqueId().equals(target.getUniqueId())) {
            messages.send(sender, "chat.cannot-message-self");
            return true;
        }
        if (!valid(content)) {
            messages.send(sender, "chat.invalid-content",
                    Placeholder.unparsed("maximum", Integer.toString(policy.maximumMessageLength())));
            return true;
        }
        complete(sender, database.submit(() -> chat.touchConversation(
                sender.getUniqueId(), target.getUniqueId(), System.currentTimeMillis())), result -> {
            if (result != ChatResult.SUCCESS) {
                error(sender, result);
                return;
            }
            Player currentTarget = Bukkit.getPlayer(target.getUniqueId());
            if (currentTarget == null) {
                messages.send(sender, "chat.target-offline");
                return;
            }
            Component message = Component.text(content.trim());
            messages.send(sender, "chat.message-sent",
                    Placeholder.unparsed("player", currentTarget.getName()), Placeholder.component("message", message));
            messages.send(currentTarget, "chat.message-received",
                    Placeholder.unparsed("player", sender.getName()), Placeholder.component("message", message));
        });
        return true;
    }

    private boolean mail(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length == 0) return inbox(sender, player, 1);
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "send" -> sendMail(sender, player, Arrays.copyOfRange(args, 1, args.length));
            case "inbox" -> {
                int page = args.length == 2 ? positiveInt(args[1]) : 1;
                if (args.length > 2 || page < 1) yield usage(sender, "/mail inbox [page]");
                yield inbox(sender, player, page);
            }
            case "read" -> readMail(sender, player, Arrays.copyOfRange(args, 1, args.length));
            case "delete" -> deleteMail(sender, player, Arrays.copyOfRange(args, 1, args.length));
            default -> usage(sender, "/mail <send|inbox|read|delete>");
        };
    }

    private boolean sendMail(CommandSender sender, Player player, String[] args) {
        if (args.length < 2) return usage(sender, "/mail send <player> <message>");
        String content = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        complete(sender, database.submit(() -> {
            CitizenProfile target = citizens.findByName(args[0]).orElse(null);
            return target == null ? new MailSend(ChatOperation.result(ChatResult.CITIZEN_NOT_FOUND), null)
                    : new MailSend(chat.sendMail(player.getUniqueId(), target.uuid(), content,
                    policy.maximumMessageLength(), System.currentTimeMillis()), target);
        }), result -> {
            if (result.operation().result() != ChatResult.SUCCESS) {
                error(sender, result.operation().result());
                return;
            }
            MailMessage mail = result.operation().value().orElseThrow();
            messages.send(sender, "chat.mail-sent",
                    Placeholder.unparsed("player", result.target().lastName()),
                    Placeholder.unparsed("id", Long.toString(mail.id())));
            Player online = Bukkit.getPlayer(result.target().uuid());
            if (online != null) messages.send(online, "chat.mail-received",
                    Placeholder.unparsed("player", player.getName()));
        });
        return true;
    }

    private boolean inbox(CommandSender sender, Player player, int page) {
        int offset = (page - 1) * policy.mailPageSize();
        complete(sender, database.submit(() -> chat.inbox(
                player.getUniqueId(), policy.mailPageSize(), offset)), inbox -> {
            messages.send(sender, "chat.mail-header", Placeholder.unparsed("page", Integer.toString(page)));
            if (inbox.isEmpty()) messages.send(sender, "chat.mail-empty");
            for (MailMessage mail : inbox) messages.send(sender, "chat.mail-entry",
                    Placeholder.unparsed("id", Long.toString(mail.id())),
                    Placeholder.unparsed("sender", mail.senderName()),
                    Placeholder.unparsed("date", DATE.format(mail.sentAt())),
                    Placeholder.component("status", messages.component(sender,
                            mail.readAt() == null ? "chat.mail-unread" : "chat.mail-read")));
        });
        return true;
    }

    private boolean readMail(CommandSender sender, Player player, String[] args) {
        Long id = singleId(args);
        if (id == null) return usage(sender, "/mail read <id>");
        complete(sender, database.submit(() -> chat.readMail(
                player.getUniqueId(), id, System.currentTimeMillis())), operation -> {
            if (operation.result() != ChatResult.SUCCESS) {
                error(sender, operation.result());
                return;
            }
            MailMessage mail = operation.value().orElseThrow();
            messages.send(sender, "chat.mail-content",
                    Placeholder.unparsed("id", Long.toString(mail.id())),
                    Placeholder.unparsed("sender", mail.senderName()),
                    Placeholder.unparsed("date", DATE.format(mail.sentAt())),
                    Placeholder.unparsed("message", mail.content()));
        });
        return true;
    }

    private boolean deleteMail(CommandSender sender, Player player, String[] args) {
        Long id = singleId(args);
        if (id == null) return usage(sender, "/mail delete <id>");
        complete(sender, database.submit(() -> chat.deleteMail(
                player.getUniqueId(), id, System.currentTimeMillis())), result -> {
            if (result != ChatResult.SUCCESS) error(sender, result);
            else messages.send(sender, "chat.mail-deleted", Placeholder.unparsed("id", Long.toString(id)));
        });
        return true;
    }

    private boolean advertise(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length == 0) return usage(sender, "/ad <message>");
        String content = String.join(" ", args);
        complete(sender, database.submit(() -> chat.submitAdvertisement(
                player.getUniqueId(), content, policy.maximumMessageLength(),
                policy.advertisementCooldown(), System.currentTimeMillis())), attempt -> {
            if (attempt.result() == ChatResult.COOLDOWN) {
                long seconds = Math.max(1, (attempt.remainingCooldownMillis() + 999) / 1_000);
                messages.send(sender, "chat.ad-cooldown",
                        Placeholder.unparsed("seconds", Long.toString(seconds)));
            } else if (attempt.result() != ChatResult.SUCCESS) {
                error(sender, attempt.result());
            } else {
                router.broadcastSpecial("chat.format.advertisement", player,
                        Component.text(attempt.advertisement().orElseThrow().content()));
            }
        });
        return true;
    }

    private boolean ask(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length == 0) return usage(sender, "/ask <message>");
        String content = String.join(" ", args);
        if (!valid(content)) {
            messages.send(sender, "chat.invalid-content",
                    Placeholder.unparsed("maximum", Integer.toString(policy.maximumMessageLength())));
            return true;
        }
        router.broadcastSpecial("chat.format.help", player, Component.text(content));
        return true;
    }

    private boolean ignore(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length == 0) {
            complete(sender, database.submit(() -> chat.ignoredPlayers(player.getUniqueId())), ignored -> {
                messages.send(sender, "chat.ignore-header");
                if (ignored.isEmpty()) {
                    messages.send(sender, "chat.ignore-empty");
                    return;
                }
                for (IgnoredPlayer entry : ignored) {
                    messages.send(sender, "chat.ignore-entry",
                            Placeholder.unparsed("player", entry.playerName()));
                }
            });
            return true;
        }
        if (args.length != 1) return usage(sender, "/ignore [player]");
        return changeIgnore(sender, player, args[0], true);
    }

    private boolean unignore(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 1) return usage(sender, "/unignore <player>");
        return changeIgnore(sender, player, args[0], false);
    }

    private boolean changeIgnore(CommandSender sender, Player player, String targetName, boolean ignored) {
        complete(sender, database.submit(() -> {
            CitizenProfile target = citizens.findByName(targetName).orElse(null);
            ChatResult result = target == null ? ChatResult.CITIZEN_NOT_FOUND
                    : ignored
                            ? chat.ignore(player.getUniqueId(), target.uuid(), System.currentTimeMillis())
                            : chat.unignore(player.getUniqueId(), target.uuid());
            return new IgnoreAction(target, result);
        }), action -> {
            if (action.target() == null) {
                messages.send(sender, "error.player-not-found",
                        Placeholder.unparsed("player", targetName));
                return;
            }
            if (action.result() == ChatResult.SUCCESS) {
                router.updateIgnore(player.getUniqueId(), action.target().uuid(), ignored);
                messages.send(sender, ignored ? "chat.ignored" : "chat.unignored",
                        Placeholder.unparsed("player", action.target().lastName()));
                return;
            }
            String key = switch (action.result()) {
                case CANNOT_MESSAGE_SELF -> "chat.cannot-ignore-self";
                case ALREADY_IGNORED -> "chat.already-ignored";
                case NOT_IGNORED -> "chat.not-ignored";
                default -> null;
            };
            if (key == null) {
                error(sender, action.result());
            } else {
                messages.send(sender, key,
                        Placeholder.unparsed("player", action.target().lastName()));
            }
        });
        return true;
    }

    private boolean timestamp(CommandSender sender, String[] args) {
        if (args.length != 0) return usage(sender, "/timestamp");
        messages.send(sender, "chat.timestamp",
                Placeholder.unparsed("time", LOCAL_TIME.withZone(policy.timeZone()).format(Instant.now())));
        return true;
    }

    private boolean valid(String content) {
        String trimmed = content.trim();
        return !trimmed.isEmpty() && trimmed.length() <= policy.maximumMessageLength();
    }

    private void error(CommandSender sender, ChatResult result) {
        messages.send(sender, switch (result) {
            case CITIZEN_NOT_FOUND -> "error.player-not-found";
            case TARGET_OFFLINE -> "chat.target-offline";
            case CANNOT_MESSAGE_SELF -> "chat.cannot-message-self";
            case ALREADY_IGNORED -> "chat.already-ignored";
            case NOT_IGNORED -> "chat.not-ignored";
            case TARGET_IGNORES_SENDER -> "chat.contact-blocked";
            case NOT_AUTHORIZED -> "chat.department-denied";
            case MISSING_QUALIFICATION -> "chat.ad-qualification";
            case MAIL_NOT_FOUND -> "chat.mail-not-found";
            case INVALID_CONTENT -> "chat.invalid-content";
            case COOLDOWN -> "chat.ad-cooldown";
            case SUCCESS -> "chat.operation-complete";
        }, Placeholder.unparsed("maximum", Integer.toString(policy.maximumMessageLength())),
                Placeholder.unparsed("seconds", "1"));
    }

    private <T> void complete(CommandSender sender, CompletableFuture<T> future,
                              java.util.function.Consumer<T> success) {
        future.whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                plugin.getLogger().log(Level.SEVERE, "Chat operation failed", cause);
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

    private static int positiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static Long singleId(String[] args) {
        if (args.length != 1) return null;
        try {
            long id = Long.parseLong(args[0]);
            return id > 0 ? id : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String departmentCommand(ChatChannel channel) {
        return switch (channel) {
            case DOJ -> "doj";
            case SENATE -> "sen";
            case JUDICIARY -> "jud";
            default -> throw new IllegalArgumentException("Not a department channel");
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("msg") && args.length == 1) return onlineNames(args[0]);
        if ((name.equals("ignore") || name.equals("unignore")) && args.length == 1) {
            return onlineNames(args[0]);
        }
        if (!name.equals("mail")) return List.of();
        if (args.length == 1) return filter(List.of("send", "inbox", "read", "delete"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("send")) return onlineNames(args[1]);
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

    private record MailSend(ChatOperation<MailMessage> operation, CitizenProfile target) {
    }

    private record IgnoreAction(CitizenProfile target, ChatResult result) {
    }
}
