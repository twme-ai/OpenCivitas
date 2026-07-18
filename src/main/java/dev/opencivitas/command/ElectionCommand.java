package dev.opencivitas.command;

import dev.opencivitas.citizen.CitizenProfile;
import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.election.Election;
import dev.opencivitas.election.ElectionActionResult;
import dev.opencivitas.election.ElectionChoice;
import dev.opencivitas.election.ElectionDefinition;
import dev.opencivitas.election.ElectionDetails;
import dev.opencivitas.election.ElectionKind;
import dev.opencivitas.election.ElectionOperation;
import dev.opencivitas.election.ElectionPhase;
import dev.opencivitas.election.ElectionRegistry;
import dev.opencivitas.election.ElectionRepository;
import dev.opencivitas.election.ElectionResultEntry;
import dev.opencivitas.election.OfficeTerm;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class ElectionCommand implements CommandExecutor, TabCompleter {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);
    private static final int PAGE_SIZE = 10;

    private final JavaPlugin plugin;
    private final Database database;
    private final CitizenRepository citizens;
    private final ElectionRepository elections;
    private final ElectionRegistry registry;
    private final MessageService messages;

    public ElectionCommand(
            JavaPlugin plugin,
            Database database,
            CitizenRepository citizens,
            ElectionRepository elections,
            ElectionRegistry registry,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.citizens = citizens;
        this.elections = elections;
        this.registry = registry;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length == 0) return list(sender, new String[0]);
        String[] tail = Arrays.copyOfRange(args, 1, args.length);
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender, tail);
            case "offices" -> offices(sender, tail);
            case "info" -> info(sender, tail);
            case "nominate", "run" -> nominate(sender, tail);
            case "withdraw" -> withdraw(sender, tail);
            case "ballot", "vote" -> ballot(sender, tail);
            case "results" -> results(sender, tail);
            case "terms", "officeholders" -> terms(sender, tail);
            case "create" -> create(sender, tail);
            case "referendum" -> referendum(sender, tail);
            case "close" -> close(sender, tail);
            case "cancel" -> cancel(sender, tail);
            default -> {
                usage(sender, "/election <list|offices|info|nominate|withdraw|ballot|results|terms>");
                yield true;
            }
        };
    }

    private boolean list(CommandSender sender, String[] args) {
        int page = page(sender, args);
        if (page < 1) return true;
        long now = System.currentTimeMillis();
        complete(sender, database.submit(() -> elections.list(PAGE_SIZE, (page - 1) * PAGE_SIZE)), found -> {
            messages.send(sender, "elections.list-header", Placeholder.unparsed("page", Integer.toString(page)));
            if (found.isEmpty()) messages.send(sender, "elections.list-empty");
            for (Election election : found) {
                messages.send(sender, "elections.list-entry",
                        Placeholder.unparsed("id", Long.toString(election.id())),
                        Placeholder.unparsed("slug", election.slug()),
                        Placeholder.component("title", title(sender, election)),
                        Placeholder.component("phase", phase(sender, election.phase(now))));
            }
        });
        return true;
    }

    private boolean offices(CommandSender sender, String[] args) {
        if (args.length != 0) {
            usage(sender, "/election offices");
            return true;
        }
        messages.send(sender, "elections.offices-header");
        for (ElectionDefinition definition : registry.all()) {
            messages.send(sender, "elections.office-entry",
                    Placeholder.component("office", office(sender, definition.id())),
                    Placeholder.unparsed("method", definition.method().name()),
                    Placeholder.unparsed("seats", Integer.toString(definition.seats())),
                    Placeholder.unparsed("days", Integer.toString(definition.termDays())),
                    Placeholder.unparsed("total", hours(definition.minimumTotalPlaytime())),
                    Placeholder.unparsed("recent", hours(definition.minimumRecentPlaytime())));
        }
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        Long id = electionId(sender, args, "/election info <id>");
        if (id == null) return true;
        long now = System.currentTimeMillis();
        complete(sender, database.submit(() -> elections.details(id)), details -> {
            if (details.isEmpty()) {
                messages.send(sender, "elections.not-found", Placeholder.unparsed("id", Long.toString(id)));
                return;
            }
            ElectionDetails selected = details.get();
            Election election = selected.election();
            messages.send(sender, "elections.info-header",
                    Placeholder.unparsed("id", Long.toString(id)),
                    Placeholder.component("title", title(sender, election)));
            messages.send(sender, "elections.info-state",
                    Placeholder.component("phase", phase(sender, election.phase(now))),
                    Placeholder.unparsed("method", election.method().name()),
                    Placeholder.unparsed("seats", Integer.toString(election.seats())),
                    Placeholder.unparsed("ballots", Integer.toString(selected.ballotCount())));
            messages.send(sender, "elections.info-dates",
                    Placeholder.unparsed("nominations", DATE.format(election.nominationsCloseAt())),
                    Placeholder.unparsed("voting", DATE.format(election.votingClosesAt())));
            if (selected.choices().isEmpty()) messages.send(sender, "elections.candidates-empty");
            for (ElectionChoice choice : selected.choices()) candidate(sender, choice);
        });
        return true;
    }

    private boolean nominate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            usage(sender, "/election nominate <id> [running-mate]");
            return true;
        }
        Long id = positiveLong(args[0]);
        if (id == null) {
            messages.send(sender, "elections.invalid-id");
            return true;
        }
        long now = System.currentTimeMillis();
        complete(sender, database.submit(() -> {
            Optional<ElectionDetails> details = elections.details(id);
            if (details.isEmpty()) return ElectionOperation.result(ElectionActionResult.NOT_FOUND);
            Election election = details.get().election();
            Optional<ElectionDefinition> definition = election.officeId() == null
                    ? Optional.empty() : registry.find(election.officeId());
            if (definition.isEmpty()) return ElectionOperation.result(ElectionActionResult.INVALID_PHASE);
            UUID mate = null;
            citizens.heartbeatActivity(player.getUniqueId(), now);
            if (args.length == 2) {
                Optional<CitizenProfile> profile = citizens.findByName(args[1]);
                if (profile.isEmpty()) {
                    return ElectionOperation.result(ElectionActionResult.RUNNING_MATE_NOT_FOUND);
                }
                mate = profile.get().uuid();
            }
            return elections.nominate(id, player.getUniqueId(), mate, definition.get(), now);
        }), operation -> {
            if (operation.result() == ElectionActionResult.SUCCESS) {
                messages.send(sender, "elections.nominated", Placeholder.unparsed("id", Long.toString(id)));
            } else {
                actionError(sender, operation.result(), id);
            }
        });
        return true;
    }

    private boolean withdraw(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        Long id = electionId(sender, args, "/election withdraw <id>");
        if (id == null) return true;
        complete(sender, database.submit(() -> elections.withdraw(
                id, player.getUniqueId(), System.currentTimeMillis())), operation -> {
            if (operation.result() == ElectionActionResult.SUCCESS) {
                messages.send(sender, "elections.withdrawn", Placeholder.unparsed("id", Long.toString(id)));
            } else {
                actionError(sender, operation.result(), id);
            }
        });
        return true;
    }

    private boolean ballot(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length < 2) {
            usage(sender, "/election ballot <id> <first-choice> [second-choice ...]");
            return true;
        }
        Long id = positiveLong(args[0]);
        if (id == null) {
            messages.send(sender, "elections.invalid-id");
            return true;
        }
        complete(sender, database.submit(() -> {
            Optional<ElectionDetails> details = elections.details(id);
            if (details.isEmpty()) return ElectionOperation.result(ElectionActionResult.NOT_FOUND);
            List<String> resolved = resolveChoices(
                    details.get().choices(), Arrays.copyOfRange(args, 1, args.length));
            if (resolved.isEmpty()) return ElectionOperation.result(ElectionActionResult.INVALID_CHOICE);
            return elections.castBallot(
                    id, player.getUniqueId(), resolved, System.currentTimeMillis());
        }), operation -> {
            if (operation.result() == ElectionActionResult.SUCCESS) {
                messages.send(sender, "elections.ballot-cast", Placeholder.unparsed("id", Long.toString(id)));
            } else {
                actionError(sender, operation.result(), id);
            }
        });
        return true;
    }

    private boolean results(CommandSender sender, String[] args) {
        Long id = electionId(sender, args, "/election results <id>");
        if (id == null) return true;
        complete(sender, database.submit(() -> elections.details(id)), details -> {
            if (details.isEmpty()) {
                messages.send(sender, "elections.not-found", Placeholder.unparsed("id", Long.toString(id)));
                return;
            }
            if (details.get().election().phase(System.currentTimeMillis()) != ElectionPhase.CLOSED) {
                messages.send(sender, "elections.results-pending");
                return;
            }
            messages.send(sender, "elections.results-header",
                    Placeholder.unparsed("id", Long.toString(id)),
                    Placeholder.component("title", title(sender, details.get().election())));
            if (details.get().results().isEmpty()) messages.send(sender, "elections.results-empty");
            for (ElectionResultEntry result : details.get().results()) {
                ElectionChoice choice = details.get().choices().stream()
                        .filter(candidate -> candidate.id().equals(result.choiceId())).findFirst().orElse(null);
                String name = choice == null ? result.choiceId() : choice.displayName();
                messages.send(sender, result.elected() ? "elections.result-elected" : "elections.result-entry",
                        Placeholder.unparsed("place", Integer.toString(result.placement())),
                        Placeholder.unparsed("choice", name),
                        Placeholder.unparsed("votes", votes(result.finalTally())));
            }
        });
        return true;
    }

    private boolean terms(CommandSender sender, String[] args) {
        if (args.length != 0) {
            usage(sender, "/election terms");
            return true;
        }
        complete(sender, database.submit(() -> elections.activeTerms(System.currentTimeMillis())), terms -> {
            messages.send(sender, "elections.terms-header");
            if (terms.isEmpty()) messages.send(sender, "elections.terms-empty");
            for (OfficeTerm term : terms) {
                messages.send(sender, "elections.term-entry",
                        Placeholder.component("office", office(sender, term.officeId())),
                        Placeholder.unparsed("seat", Integer.toString(term.seatNumber())),
                        Placeholder.unparsed("player", term.holderName()),
                        Placeholder.unparsed("ends", DATE.format(term.endsAt())));
            }
        });
        return true;
    }

    private boolean create(CommandSender sender, String[] args) {
        if (!manage(sender)) return true;
        if (args.length != 4) {
            usage(sender, "/election create <slug> <office> <nomination-hours> <voting-hours>");
            return true;
        }
        String slug = args[0].toLowerCase(Locale.ROOT);
        Optional<ElectionDefinition> definition = registry.find(args[1]);
        Integer nominations = hours(args[2]);
        Integer voting = hours(args[3]);
        if (!ID.matcher(slug).matches() || definition.isEmpty() || nominations == null || voting == null) {
            messages.send(sender, "elections.invalid-create");
            return true;
        }
        long now = System.currentTimeMillis();
        long nominationsClose;
        long votingClose;
        try {
            nominationsClose = Math.addExact(now, Duration.ofHours(nominations).toMillis());
            votingClose = Math.addExact(nominationsClose, Duration.ofHours(voting).toMillis());
        } catch (ArithmeticException exception) {
            messages.send(sender, "elections.invalid-create");
            return true;
        }
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        complete(sender, database.submit(() -> elections.createOffice(
                actor, slug, definition.get(), now, nominationsClose, votingClose)), operation -> {
            if (operation.result() == ElectionActionResult.SUCCESS) {
                messages.send(sender, "elections.created",
                        Placeholder.unparsed("id", Long.toString(operation.election().orElseThrow().id())),
                        Placeholder.component("office", office(sender, definition.get().id())));
            } else {
                actionError(sender, operation.result(), 0);
            }
        });
        return true;
    }

    private boolean referendum(CommandSender sender, String[] args) {
        if (!manage(sender)) return true;
        if (args.length < 3) {
            usage(sender, "/election referendum <slug> <voting-hours> <question...>");
            return true;
        }
        String slug = args[0].toLowerCase(Locale.ROOT);
        Integer voting = hours(args[1]);
        String question = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
        if (!ID.matcher(slug).matches() || voting == null || question.isBlank() || question.length() > 160) {
            messages.send(sender, "elections.invalid-create");
            return true;
        }
        long now = System.currentTimeMillis();
        long votingClose;
        try {
            votingClose = Math.addExact(now, Duration.ofHours(voting).toMillis());
        } catch (ArithmeticException exception) {
            messages.send(sender, "elections.invalid-create");
            return true;
        }
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        complete(sender, database.submit(() -> elections.createReferendum(
                actor, slug, question, now, votingClose)), operation -> {
            if (operation.result() == ElectionActionResult.SUCCESS) {
                messages.send(sender, "elections.referendum-created",
                        Placeholder.unparsed("id", Long.toString(operation.election().orElseThrow().id())));
            } else {
                actionError(sender, operation.result(), 0);
            }
        });
        return true;
    }

    private boolean close(CommandSender sender, String[] args) {
        if (!manage(sender)) return true;
        Long id = electionId(sender, args, "/election close <id>");
        if (id == null) return true;
        complete(sender, database.submit(() -> elections.close(id, System.currentTimeMillis())), operation -> {
            if (operation.result() == ElectionActionResult.SUCCESS) {
                messages.send(sender, "elections.closed", Placeholder.unparsed("id", Long.toString(id)));
            } else {
                actionError(sender, operation.result(), id);
            }
        });
        return true;
    }

    private boolean cancel(CommandSender sender, String[] args) {
        if (!manage(sender)) return true;
        Long id = electionId(sender, args, "/election cancel <id>");
        if (id == null) return true;
        complete(sender, database.submit(() -> elections.cancel(id, System.currentTimeMillis())), operation -> {
            if (operation.result() == ElectionActionResult.SUCCESS) {
                messages.send(sender, "elections.cancelled", Placeholder.unparsed("id", Long.toString(id)));
            } else {
                actionError(sender, operation.result(), id);
            }
        });
        return true;
    }

    private void candidate(CommandSender sender, ElectionChoice choice) {
        if (!choice.active()) return;
        if (choice.runningMateName() == null) {
            messages.send(sender, "elections.candidate-entry",
                    Placeholder.unparsed("candidate", choice.displayName()));
        } else {
            messages.send(sender, "elections.slate-entry",
                    Placeholder.unparsed("candidate", choice.displayName()),
                    Placeholder.unparsed("mate", choice.runningMateName()));
        }
    }

    private void actionError(CommandSender sender, ElectionActionResult result, long id) {
        String key = switch (result) {
            case NOT_FOUND -> "elections.not-found";
            case SLUG_EXISTS -> "elections.slug-exists";
            case INVALID_TIMELINE, INVALID_PHASE -> "elections.invalid-phase";
            case NOT_ENDED -> "elections.not-ended";
            case PLAYER_NOT_FOUND -> "error.player-not-found";
            case RUNNING_MATE_REQUIRED -> "elections.mate-required";
            case RUNNING_MATE_NOT_ALLOWED -> "elections.mate-not-allowed";
            case RUNNING_MATE_SELF -> "elections.mate-self";
            case RUNNING_MATE_NOT_FOUND -> "elections.mate-not-found";
            case RUNNING_MATE_INELIGIBLE_CITIZENSHIP -> "elections.mate-ineligible-citizenship";
            case RUNNING_MATE_INELIGIBLE_TOTAL_PLAYTIME -> "elections.mate-ineligible-total";
            case RUNNING_MATE_INELIGIBLE_RECENT_PLAYTIME -> "elections.mate-ineligible-recent";
            case RUNNING_MATE_INELIGIBLE_HISTORY -> "elections.mate-ineligible-history";
            case ALREADY_NOMINATED -> "elections.already-nominated";
            case NOT_NOMINATED -> "elections.not-nominated";
            case INELIGIBLE_CITIZENSHIP -> "elections.ineligible-citizenship";
            case INELIGIBLE_TOTAL_PLAYTIME -> "elections.ineligible-total";
            case INELIGIBLE_RECENT_PLAYTIME -> "elections.ineligible-recent";
            case INELIGIBLE_REELECTION -> "elections.ineligible-reelection";
            case EMPTY_BALLOT -> "elections.empty-ballot";
            case DUPLICATE_CHOICE -> "elections.duplicate-choice";
            case INVALID_CHOICE -> "elections.invalid-choice";
            case ALREADY_CLOSED -> "elections.already-closed";
            case SUCCESS -> throw new IllegalArgumentException("Success is not an error");
        };
        messages.send(sender, key,
                Placeholder.unparsed("id", Long.toString(id)),
                Placeholder.unparsed("player", ""));
    }

    private Component title(CommandSender sender, Election election) {
        return election.kind() == ElectionKind.OFFICE
                ? office(sender, election.officeId()) : Component.text(election.title());
    }

    private Component office(CommandSender sender, String id) {
        return switch (id) {
            case "house" -> messages.component(sender, "elections.office.house");
            case "senate-a" -> messages.component(sender, "elections.office.senate-a");
            case "senate-b" -> messages.component(sender, "elections.office.senate-b");
            case "president" -> messages.component(sender, "elections.office.president");
            case "vice-president" -> messages.component(sender, "elections.office.vice-president");
            default -> Component.text(displayName(id));
        };
    }

    private Component phase(CommandSender sender, ElectionPhase phase) {
        return messages.component(sender, "elections.phase." + phase.name().toLowerCase(Locale.ROOT));
    }

    private static List<String> resolveChoices(List<ElectionChoice> choices, String[] requested) {
        List<String> resolved = new ArrayList<>();
        for (String token : requested) {
            List<ElectionChoice> matches = choices.stream()
                    .filter(ElectionChoice::active)
                    .filter(choice -> choice.id().equalsIgnoreCase(token)
                            || choice.displayName().equalsIgnoreCase(token))
                    .toList();
            if (matches.size() != 1) return List.of();
            resolved.add(matches.getFirst().id());
        }
        return resolved;
    }

    private int page(CommandSender sender, String[] args) {
        if (args.length > 1) {
            usage(sender, "/election list [page]");
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

    private Long electionId(CommandSender sender, String[] args, String syntax) {
        if (args.length != 1) {
            usage(sender, syntax);
            return null;
        }
        Long id = positiveLong(args[0]);
        if (id == null) messages.send(sender, "elections.invalid-id");
        return id;
    }

    private boolean manage(CommandSender sender) {
        if (sender.hasPermission("opencivitas.elections.manage")) return true;
        messages.send(sender, "error.no-permission");
        return false;
    }

    private static Integer hours(String value) {
        try {
            int hours = Integer.parseInt(value);
            return hours >= 1 && hours <= 8_760 ? hours : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String hours(Duration duration) {
        return Long.toString(duration.toHours());
    }

    private static Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String votes(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0).toPlainString() : normalized.toPlainString();
    }

    private static String displayName(String id) {
        String[] words = id.split("-");
        for (int index = 0; index < words.length; index++) {
            if (!words[index].isEmpty()) {
                words[index] = Character.toUpperCase(words[index].charAt(0)) + words[index].substring(1);
            }
        }
        return String.join(" ", words);
    }

    private List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList();
    }

    private void usage(CommandSender sender, String syntax) {
        messages.send(sender, "error.usage", Placeholder.unparsed("usage", syntax));
    }

    private <T> void complete(CommandSender sender, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((value, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "An asynchronous election command failed", error);
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
        if (args.length == 1) {
            List<String> commands = new ArrayList<>(List.of(
                    "list", "offices", "info", "nominate", "withdraw", "ballot", "results", "terms"));
            if (sender.hasPermission("opencivitas.elections.manage")) {
                commands.addAll(List.of("create", "referendum", "close", "cancel"));
            }
            return filter(args[0], commands);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("nominate")) {
            return filter(args[2], onlineNames());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            return filter(args[2], registry.all().stream().map(ElectionDefinition::id).toList());
        }
        return List.of();
    }

    private static List<String> filter(String prefix, List<String> values) {
        String lowered = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lowered)).toList();
    }
}
