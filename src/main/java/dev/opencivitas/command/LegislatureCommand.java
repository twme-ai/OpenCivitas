package dev.opencivitas.command;

import dev.opencivitas.database.Database;
import dev.opencivitas.legislature.BillStatus;
import dev.opencivitas.legislature.BillType;
import dev.opencivitas.legislature.EnactedLaw;
import dev.opencivitas.legislature.LegislativeActionResult;
import dev.opencivitas.legislature.LegislativeAmendment;
import dev.opencivitas.legislature.LegislativeBill;
import dev.opencivitas.legislature.LegislativeDetails;
import dev.opencivitas.legislature.LegislativeEvent;
import dev.opencivitas.legislature.LegislativeOperation;
import dev.opencivitas.legislature.LegislativeVote;
import dev.opencivitas.legislature.LegislativeVoteResult;
import dev.opencivitas.legislature.LegislatureRepository;
import dev.opencivitas.legislature.LegislatureService;
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
import org.jetbrains.annotations.NotNull;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class LegislatureCommand implements CommandExecutor, TabCompleter {
    private static final int PAGE_SIZE = 10;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final JavaPlugin plugin;
    private final Database database;
    private final LegislatureRepository legislature;
    private final LegislatureService service;
    private final MessageService messages;

    public LegislatureCommand(
            JavaPlugin plugin,
            Database database,
            LegislatureRepository legislature,
            LegislatureService service,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.legislature = legislature;
        this.service = service;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "laws" -> laws(sender, args);
            case "law" -> law(sender, args);
            case "bill" -> bill(sender, args);
            default -> false;
        };
    }

    private boolean bill(CommandSender sender, String[] args) {
        if (args.length == 0) return list(sender, new String[0]);
        String[] tail = Arrays.copyOfRange(args, 1, args.length);
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender, tail);
            case "info" -> info(sender, tail);
            case "text" -> text(sender, tail);
            case "events", "history" -> events(sender, tail);
            case "create" -> create(sender, tail);
            case "submit" -> submit(sender, tail);
            case "withdraw" -> withdraw(sender, tail);
            case "amend" -> amend(sender, tail);
            case "vote" -> vote(sender, tail);
            case "tally" -> tally(sender, tail);
            case "assent" -> presidential(sender, tail, true);
            case "veto" -> presidential(sender, tail, false);
            case "override" -> override(sender, tail);
            default -> {
                usage(sender, "/bill <list|info|text|events|create|submit|withdraw|amend|vote|tally|assent|veto|override>");
                yield true;
            }
        };
    }

    private boolean list(CommandSender sender, String[] args) {
        int page = page(sender, args, "/bill list [page]");
        if (page < 1) return true;
        complete(sender, database.submit(() -> legislature.list(
                PAGE_SIZE, (page - 1) * PAGE_SIZE)), bills -> {
            messages.send(sender, "legislature.list-header",
                    Placeholder.unparsed("page", Integer.toString(page)));
            if (bills.isEmpty()) messages.send(sender, "legislature.list-empty");
            for (LegislativeBill bill : bills) {
                messages.send(sender, "legislature.list-entry",
                        Placeholder.unparsed("id", Long.toString(bill.id())),
                        Placeholder.unparsed("number", bill.number()),
                        Placeholder.unparsed("title", bill.title()),
                        Placeholder.component("status", status(sender, bill.status())));
            }
        });
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        Long id = id(sender, args, "/bill info <id>");
        if (id == null) return true;
        complete(sender, database.submit(() -> legislature.details(id)), details -> {
            if (details.isEmpty()) {
                notFound(sender, id);
                return;
            }
            LegislativeBill bill = details.get().bill();
            messages.send(sender, "legislature.info-header",
                    Placeholder.unparsed("number", bill.number()),
                    Placeholder.unparsed("title", bill.title()));
            messages.send(sender, "legislature.info-meta",
                    Placeholder.component("type", type(sender, bill.type())),
                    Placeholder.component("status", status(sender, bill.status())),
                    Placeholder.unparsed("author", bill.authorName()),
                    Placeholder.unparsed("date", DATE.format(bill.createdAt())));
            if (bill.presidentialDeadline() != null) {
                messages.send(sender, "legislature.info-deadline",
                        Placeholder.unparsed("date", DATE.format(bill.presidentialDeadline())));
            }
            if (bill.vetoReason() != null) {
                messages.send(sender, "legislature.info-veto",
                        Placeholder.unparsed("reason", bill.vetoReason()));
            }
            if (bill.referendumElectionId() != null) {
                messages.send(sender, "legislature.info-referendum",
                        Placeholder.unparsed("id", Long.toString(bill.referendumElectionId())));
            }
            for (LegislativeVoteResult result : details.get().voteResults()) result(sender, result);
            for (LegislativeAmendment amendment : details.get().amendments()) amendment(sender, amendment);
        });
        return true;
    }

    private boolean text(CommandSender sender, String[] args) {
        Long id = id(sender, args, "/bill text <id>");
        if (id == null) return true;
        complete(sender, database.submit(() -> legislature.details(id)), details -> {
            if (details.isEmpty()) {
                notFound(sender, id);
                return;
            }
            LegislativeBill bill = details.get().bill();
            messages.send(sender, "legislature.text-header",
                    Placeholder.unparsed("number", bill.number()),
                    Placeholder.unparsed("title", bill.title()));
            messages.send(sender, "legislature.text-body", Placeholder.unparsed("body", bill.body()));
            for (LegislativeAmendment amendment : details.get().amendments()) amendment(sender, amendment);
        });
        return true;
    }

    private boolean events(CommandSender sender, String[] args) {
        Long id = id(sender, args, "/bill events <id>");
        if (id == null) return true;
        complete(sender, database.submit(() -> legislature.details(id)), details -> {
            if (details.isEmpty()) {
                notFound(sender, id);
                return;
            }
            messages.send(sender, "legislature.events-header",
                    Placeholder.unparsed("number", details.get().bill().number()));
            for (LegislativeEvent event : details.get().events()) {
                messages.send(sender, "legislature.event-entry",
                        Placeholder.unparsed("date", DATE.format(event.createdAt())),
                        Placeholder.component("event", event(sender, event.type())),
                        Placeholder.component("actor", event.actorName() == null
                                ? messages.component(sender, "legislature.system")
                                : Component.text(event.actorName())),
                        Placeholder.component("detail", eventDetail(sender, event)));
            }
        });
        return true;
    }

    private boolean create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length < 4) {
            usage(sender, "/bill create <regular|constitutional|appropriation|resolution> <title...> -- <body...>");
            return true;
        }
        BillType type;
        try {
            type = BillType.valueOf(args[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            messages.send(sender, "legislature.invalid-type");
            return true;
        }
        int divider = -1;
        for (int index = 1; index < args.length; index++) {
            if (args[index].equals("--")) {
                divider = index;
                break;
            }
        }
        if (divider < 2 || divider == args.length - 1) {
            usage(sender, "/bill create <type> <title...> -- <body...>");
            return true;
        }
        String title = String.join(" ", Arrays.copyOfRange(args, 1, divider));
        String body = String.join(" ", Arrays.copyOfRange(args, divider + 1, args.length));
        complete(sender, database.submit(() -> legislature.createDraft(
                player.getUniqueId(), type, title, body, System.currentTimeMillis())), operation -> {
            if (operation.result() == LegislativeActionResult.SUCCESS) {
                LegislativeBill bill = operation.bill().orElseThrow();
                messages.send(sender, "legislature.created",
                        Placeholder.unparsed("id", Long.toString(bill.id())),
                        Placeholder.unparsed("number", bill.number()));
            } else actionError(sender, operation.result());
        });
        return true;
    }

    private boolean submit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        Long id = id(sender, args, "/bill submit <id>");
        if (id == null) return true;
        operate(sender, id, () -> legislature.submit(
                id, player.getUniqueId(), System.currentTimeMillis()), "legislature.submitted");
        return true;
    }

    private boolean withdraw(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        Long id = id(sender, args, "/bill withdraw <id>");
        if (id == null) return true;
        operate(sender, id, () -> legislature.withdraw(
                id, player.getUniqueId(), System.currentTimeMillis()), "legislature.withdrawn");
        return true;
    }

    private boolean amend(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length < 2) {
            usage(sender, "/bill amend <id> <amendment text...>");
            return true;
        }
        Long id = positiveLong(args[0]);
        if (id == null) {
            messages.send(sender, "legislature.invalid-id");
            return true;
        }
        String amendment = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        operate(sender, id, () -> legislature.amend(
                id, player.getUniqueId(), amendment, System.currentTimeMillis()), "legislature.amended");
        return true;
    }

    private boolean vote(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 2) {
            usage(sender, "/bill vote <id> <yes|no|abstain>");
            return true;
        }
        Long id = positiveLong(args[0]);
        LegislativeVote vote;
        try {
            vote = LegislativeVote.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            messages.send(sender, "legislature.invalid-vote");
            return true;
        }
        if (id == null) {
            messages.send(sender, "legislature.invalid-id");
            return true;
        }
        operate(sender, id, () -> legislature.vote(
                id, player.getUniqueId(), vote, System.currentTimeMillis()), "legislature.vote-cast");
        return true;
    }

    private boolean tally(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        Long id = id(sender, args, "/bill tally <id>");
        if (id == null) return true;
        long now = System.currentTimeMillis();
        complete(sender, database.submit(() -> {
            LegislativeOperation operation = legislature.tally(id, player.getUniqueId(), now);
            if (operation.result() == LegislativeActionResult.SUCCESS) service.settle(now);
            return operation;
        }), operation -> {
            if (operation.result() != LegislativeActionResult.SUCCESS) {
                actionError(sender, operation.result());
                return;
            }
            LegislativeVoteResult result = operation.voteResult().orElseThrow();
            messages.send(sender, result.passed()
                            ? "legislature.tally-passed" : "legislature.tally-failed",
                    Placeholder.unparsed("yes", Integer.toString(result.yesVotes())),
                    Placeholder.unparsed("no", Integer.toString(result.noVotes())),
                    Placeholder.unparsed("abstain", Integer.toString(result.abstainVotes())),
                    Placeholder.unparsed("quorum", Integer.toString(result.quorumRequired())),
                    Placeholder.component("status", status(sender, operation.bill().orElseThrow().status())));
        });
        return true;
    }

    private boolean presidential(CommandSender sender, String[] args, boolean assent) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if ((assent && args.length != 1) || (!assent && args.length < 2)) {
            usage(sender, assent ? "/bill assent <id>" : "/bill veto <id> <reason...>");
            return true;
        }
        Long id = positiveLong(args[0]);
        if (id == null) {
            messages.send(sender, "legislature.invalid-id");
            return true;
        }
        String reason = assent ? null : String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        long now = System.currentTimeMillis();
        complete(sender, database.submit(() -> {
            LegislativeOperation operation = legislature.presidentialAction(
                    id, player.getUniqueId(), assent, reason, now);
            if (operation.result() == LegislativeActionResult.SUCCESS
                    || operation.result() == LegislativeActionResult.PRESIDENTIAL_WINDOW_EXPIRED) {
                service.settle(now);
            }
            return operation;
        }), operation -> {
            if (operation.result() == LegislativeActionResult.SUCCESS) {
                messages.send(sender, assent ? "legislature.assented" : "legislature.vetoed",
                        Placeholder.unparsed("id", Long.toString(id)));
            } else actionError(sender, operation.result());
        });
        return true;
    }

    private boolean override(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        Long id = id(sender, args, "/bill override <id>");
        if (id == null) return true;
        operate(sender, id, () -> legislature.startOverride(
                id, player.getUniqueId(), System.currentTimeMillis()), "legislature.override-opened");
        return true;
    }

    private boolean laws(CommandSender sender, String[] args) {
        int page = page(sender, args, "/laws [page]");
        if (page < 1) return true;
        complete(sender, database.submit(() -> legislature.laws(
                PAGE_SIZE, (page - 1) * PAGE_SIZE)), laws -> {
            messages.send(sender, "laws.header", Placeholder.unparsed("page", Integer.toString(page)));
            if (laws.isEmpty()) messages.send(sender, "laws.empty");
            for (EnactedLaw law : laws) {
                messages.send(sender, "laws.entry",
                        Placeholder.unparsed("number", law.number()),
                        Placeholder.unparsed("title", law.title()),
                        Placeholder.component("type", type(sender, law.type())));
            }
        });
        return true;
    }

    private boolean law(CommandSender sender, String[] args) {
        if (args.length != 1) {
            usage(sender, "/law <number|id>");
            return true;
        }
        complete(sender, database.submit(() -> legislature.findLaw(args[0])), law -> {
            if (law.isEmpty()) {
                messages.send(sender, "laws.not-found", Placeholder.unparsed("law", args[0]));
                return;
            }
            EnactedLaw selected = law.get();
            messages.send(sender, "laws.detail-header",
                    Placeholder.unparsed("number", selected.number()),
                    Placeholder.unparsed("title", selected.title()));
            messages.send(sender, "laws.detail-meta",
                    Placeholder.component("type", type(sender, selected.type())),
                    Placeholder.unparsed("date", DATE.format(selected.enactedAt())));
            messages.send(sender, "laws.detail-body", Placeholder.unparsed("body", selected.body()));
        });
        return true;
    }

    private void result(CommandSender sender, LegislativeVoteResult result) {
        messages.send(sender, result.passed()
                        ? "legislature.result-passed" : "legislature.result-failed",
                Placeholder.component("stage", status(sender, result.stage())),
                Placeholder.unparsed("yes", Integer.toString(result.yesVotes())),
                Placeholder.unparsed("no", Integer.toString(result.noVotes())),
                Placeholder.unparsed("abstain", Integer.toString(result.abstainVotes())),
                Placeholder.component("threshold", threshold(sender, result.threshold().name())));
    }

    private void amendment(CommandSender sender, LegislativeAmendment amendment) {
        messages.send(sender, "legislature.amendment-entry",
                Placeholder.unparsed("id", Long.toString(amendment.id())),
                Placeholder.unparsed("author", amendment.authorName()),
                Placeholder.component("status", amendmentStatus(sender, amendment.status())),
                Placeholder.unparsed("text", amendment.text()));
    }

    private Component event(CommandSender sender, String event) {
        String key = "legislature.event." + event.toLowerCase(Locale.ROOT).replace('_', '-');
        return messages.component(sender, key);
    }

    private Component eventDetail(CommandSender sender, LegislativeEvent event) {
        if (event.detail() == null || event.detail().isBlank()) return Component.empty();
        if (event.type().equals("VOTE_CAST") && event.detail().contains(":")) {
            String[] parts = event.detail().split(":", 2);
            try {
                return messages.component(sender, "legislature.event-vote-detail",
                        Placeholder.component("stage", status(sender, BillStatus.valueOf(parts[0]))),
                        Placeholder.component("vote", vote(sender, parts[1])));
            } catch (IllegalArgumentException ignored) {
                return Component.text(event.detail());
            }
        }
        if (event.type().equals("DRAFT_CREATED")) {
            try {
                return type(sender, BillType.valueOf(event.detail()));
            } catch (IllegalArgumentException ignored) {
                return Component.text(event.detail());
            }
        }
        if (event.type().equals("VOTE_FAILED") || event.type().equals("QUORUM_FAILED")) {
            try {
                return status(sender, BillStatus.valueOf(event.detail()));
            } catch (IllegalArgumentException ignored) {
                return Component.text(event.detail());
            }
        }
        return Component.text(event.detail());
    }

    private Component threshold(CommandSender sender, String threshold) {
        return messages.component(sender,
                "legislature.threshold." + threshold.toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    private Component amendmentStatus(CommandSender sender, String status) {
        return messages.component(sender, "legislature.amendment-status." + status.toLowerCase(Locale.ROOT));
    }

    private Component vote(CommandSender sender, String vote) {
        return messages.component(sender, "legislature.vote." + vote.toLowerCase(Locale.ROOT));
    }

    private Component status(CommandSender sender, BillStatus status) {
        return messages.component(sender,
                "legislature.status." + status.name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    private Component type(CommandSender sender, BillType type) {
        return messages.component(sender, "legislature.type." + type.name().toLowerCase(Locale.ROOT));
    }

    private void operate(
            CommandSender sender,
            long id,
            DatabaseCall call,
            String successKey
    ) {
        complete(sender, database.submit(call::run), operation -> {
            if (operation.result() == LegislativeActionResult.SUCCESS) {
                messages.send(sender, successKey, Placeholder.unparsed("id", Long.toString(id)));
            } else actionError(sender, operation.result());
        });
    }

    private void actionError(CommandSender sender, LegislativeActionResult result) {
        String key = switch (result) {
            case NOT_FOUND -> "legislature.not-found";
            case NO_PERMISSION -> "error.no-permission";
            case INVALID_STATE -> "legislature.invalid-state";
            case INVALID_CONTENT -> "legislature.invalid-content";
            case NOT_AUTHOR -> "legislature.not-author";
            case NOT_HOUSE_MEMBER -> "legislature.not-house";
            case NOT_SENATE_MEMBER -> "legislature.not-senate";
            case NOT_PRESIDENT -> "legislature.not-president";
            case PRESIDENTIAL_WINDOW_EXPIRED -> "legislature.presidential-window-expired";
            case ALREADY_TALLIED -> "legislature.already-tallied";
            case QUORUM_FAILED -> "legislature.quorum-failed";
            case VOTE_FAILED -> "legislature.vote-failed";
            case APPROPRIATION_OVERRIDE_FORBIDDEN -> "legislature.appropriation-no-override";
            case REFERENDUM_NOT_READY -> "legislature.referendum-not-ready";
            case SUCCESS -> throw new IllegalArgumentException("Success is not an error");
        };
        messages.send(sender, key);
    }

    private int page(CommandSender sender, String[] args, String syntax) {
        if (args.length > 1) {
            usage(sender, syntax);
            return -1;
        }
        if (args.length == 0) return 1;
        Long page = positiveLong(args[0]);
        if (page == null || page > Integer.MAX_VALUE) {
            messages.send(sender, "error.invalid-page");
            return -1;
        }
        return page.intValue();
    }

    private Long id(CommandSender sender, String[] args, String syntax) {
        if (args.length != 1) {
            usage(sender, syntax);
            return null;
        }
        Long id = positiveLong(args[0]);
        if (id == null) messages.send(sender, "legislature.invalid-id");
        return id;
    }

    private void notFound(CommandSender sender, long id) {
        messages.send(sender, "legislature.not-found-id",
                Placeholder.unparsed("id", Long.toString(id)));
    }

    private boolean playerOnly(CommandSender sender) {
        messages.send(sender, "error.player-only");
        return true;
    }

    private void usage(CommandSender sender, String syntax) {
        messages.send(sender, "error.usage", Placeholder.unparsed("usage", syntax));
    }

    private static Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private <T> void complete(CommandSender sender, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((value, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "An asynchronous legislature command failed", error);
                messages.send(sender, "error.database");
            } else {
                success.accept(value);
            }
        }));
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (!command.getName().equalsIgnoreCase("bill")) return List.of();
        if (args.length == 1) {
            return filter(args[0], List.of(
                    "list", "info", "text", "events", "create", "submit", "withdraw",
                    "amend", "vote", "tally", "assent", "veto", "override"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            return filter(args[1], Arrays.stream(BillType.values())
                    .map(type -> type.name().toLowerCase(Locale.ROOT)).toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("vote")) {
            return filter(args[2], Arrays.stream(LegislativeVote.values())
                    .map(vote -> vote.name().toLowerCase(Locale.ROOT)).toList());
        }
        return List.of();
    }

    private static List<String> filter(String prefix, List<String> values) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(lowered)).toList();
    }

    @FunctionalInterface
    private interface DatabaseCall {
        LegislativeOperation run() throws Exception;
    }
}
