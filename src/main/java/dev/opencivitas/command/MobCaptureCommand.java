package dev.opencivitas.command;

import dev.opencivitas.citizen.CitizenProfile;
import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.economy.Money;
import dev.opencivitas.message.MessageService;
import dev.opencivitas.mobcapture.MobCapturePolicy;
import dev.opencivitas.mobcapture.MobCaptureRecord;
import dev.opencivitas.mobcapture.MobCaptureRepository;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class MobCaptureCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final JavaPlugin plugin;
    private final Database database;
    private final CitizenRepository citizens;
    private final MobCaptureRepository captures;
    private final MobCapturePolicy policy;
    private final MessageService messages;
    private final String currencySymbol;
    private final int pageSize;

    public MobCaptureCommand(
            JavaPlugin plugin,
            Database database,
            CitizenRepository citizens,
            MobCaptureRepository captures,
            MobCapturePolicy policy,
            MessageService messages,
            String currencySymbol,
            int pageSize
    ) {
        this.plugin = plugin;
        this.database = database;
        this.citizens = citizens;
        this.captures = captures;
        this.policy = policy;
        this.messages = messages;
        this.currencySymbol = currencySymbol;
        this.pageSize = pageSize;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args.length == 1 && args[0].equalsIgnoreCase("info")) {
            messages.send(sender, "mob-capture.info",
                    Placeholder.unparsed("fee", Money.format(policy.feeCents(), currencySymbol)),
                    Placeholder.unparsed("numerator", Integer.toString(policy.chance().numerator())),
                    Placeholder.unparsed("denominator", Integer.toString(policy.chance().denominator())),
                    Placeholder.unparsed("entities", Integer.toString(policy.entityCount())));
            return true;
        }
        if (!args[0].equalsIgnoreCase("logs") || args.length > 3) return usage(sender);
        if (!sender.hasPermission("opencivitas.mobcapture.logs")) {
            messages.send(sender, "error.no-permission");
            return true;
        }

        String playerName = null;
        int page = 1;
        if (args.length >= 2) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                playerName = args[1];
                if (args.length == 3) {
                    try {
                        page = Integer.parseInt(args[2]);
                    } catch (NumberFormatException invalidPage) {
                        messages.send(sender, "error.invalid-page");
                        return true;
                    }
                }
            }
        }
        if (page < 1 || args.length == 3 && playerName == null) {
            messages.send(sender, "error.invalid-page");
            return true;
        }
        int offset;
        try {
            offset = Math.multiplyExact(page - 1, pageSize);
        } catch (ArithmeticException exception) {
            messages.send(sender, "error.invalid-page");
            return true;
        }
        int selectedPage = page;
        String selectedName = playerName;
        complete(sender, database.submit(() -> query(selectedName, offset)), query -> {
            if (!query.playerFound()) {
                messages.send(sender, "error.player-not-found",
                        Placeholder.unparsed("player", selectedName));
                return;
            }
            messages.send(sender, "mob-capture.logs-header",
                    Placeholder.unparsed("page", Integer.toString(selectedPage)),
                    Placeholder.unparsed("player", selectedName == null ? "*" : selectedName));
            if (query.records().isEmpty()) messages.send(sender, "mob-capture.logs-empty");
            for (MobCaptureRecord record : query.records()) {
                messages.send(sender, "mob-capture.logs-entry",
                        Placeholder.unparsed("id", Long.toString(record.id())),
                        Placeholder.unparsed("date", DATE.format(record.createdAt())),
                        Placeholder.unparsed("actor", record.actorName()),
                        Placeholder.unparsed("entity", record.entityType().toLowerCase(Locale.ROOT)),
                        Placeholder.unparsed("target", record.targetId().toString()),
                        Placeholder.unparsed("job", record.jobId()),
                        Placeholder.unparsed("world", record.world()),
                        Placeholder.unparsed("x", Integer.toString((int) Math.floor(record.x()))),
                        Placeholder.unparsed("y", Integer.toString((int) Math.floor(record.y()))),
                        Placeholder.unparsed("z", Integer.toString((int) Math.floor(record.z()))),
                        Placeholder.unparsed("status", record.status().toLowerCase(Locale.ROOT)));
            }
        });
        return true;
    }

    private LogQuery query(String playerName, int offset) throws Exception {
        UUID actorId = null;
        if (playerName != null) {
            CitizenProfile citizen = citizens.findByName(playerName).orElse(null);
            if (citizen == null) return new LogQuery(false, List.of());
            actorId = citizen.uuid();
        }
        return new LogQuery(true, captures.logs(actorId, pageSize, offset));
    }

    private void complete(
            CommandSender sender,
            java.util.concurrent.CompletableFuture<LogQuery> future,
            Consumer<LogQuery> success
    ) {
        future.whenComplete((value, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not query mob capture logs", error);
                messages.send(sender, "error.database");
            } else {
                success.accept(value);
            }
        }));
    }

    private boolean usage(CommandSender sender) {
        sender.sendMessage(messages.parse("<yellow>/mobcapture [info|logs [player] [page]]</yellow>"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return prefix(List.of("info", "logs"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("logs")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT)
                            .startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

    private static List<String> prefix(List<String> values, String input) {
        String normalized = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(normalized)).toList();
    }

    private record LogQuery(boolean playerFound, List<MobCaptureRecord> records) {
    }
}
