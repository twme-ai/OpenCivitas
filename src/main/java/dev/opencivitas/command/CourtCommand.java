package dev.opencivitas.command;

import dev.opencivitas.citizen.CitizenProfile;
import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.court.AppealGround;
import dev.opencivitas.court.CourtActionResult;
import dev.opencivitas.court.CourtCase;
import dev.opencivitas.court.CourtCaseDetails;
import dev.opencivitas.court.CourtDocketEntry;
import dev.opencivitas.court.CourtOperation;
import dev.opencivitas.court.CourtOutcome;
import dev.opencivitas.court.CourtRepository;
import dev.opencivitas.court.WarrantType;
import dev.opencivitas.database.Database;
import dev.opencivitas.economy.Money;
import dev.opencivitas.message.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class CourtCommand implements CommandExecutor, TabCompleter {
    private static final int PAGE_SIZE = 10;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final JavaPlugin plugin;
    private final Database database;
    private final CitizenRepository citizens;
    private final CourtRepository courts;
    private final MessageService messages;
    private final String currencySymbol;

    public CourtCommand(
            JavaPlugin plugin, Database database, CitizenRepository citizens,
            CourtRepository courts, MessageService messages, String currencySymbol) {
        this.plugin = plugin;
        this.database = database;
        this.citizens = citizens;
        this.courts = courts;
        this.messages = messages;
        this.currencySymbol = currencySymbol;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "records" -> records(sender, args);
            case "warrants" -> warrants(sender, args);
            case "case" -> caseCommand(sender, args);
            default -> false;
        };
    }

    private boolean caseCommand(CommandSender sender, String[] args) {
        if (args.length == 0) return list(sender, new String[0]);
        String[] tail = Arrays.copyOfRange(args, 1, args.length);
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender, tail);
            case "info" -> info(sender, tail);
            case "docket" -> docket(sender, tail);
            case "file" -> file(sender, tail);
            case "counsel" -> counsel(sender, tail);
            case "claim" -> claim(sender, tail);
            case "schedule" -> schedule(sender, tail);
            case "hearing" -> hearing(sender, tail);
            case "evidence" -> evidence(sender, tail, false);
            case "evidenceitem" -> evidence(sender, tail, true);
            case "motion" -> motion(sender, tail);
            case "order" -> order(sender, tail);
            case "warrant" -> warrant(sender, tail);
            case "verdict" -> verdict(sender, tail);
            case "appeal" -> appeal(sender, tail);
            default -> {
                usage(sender, "/case <list|info|docket|file|counsel|claim|schedule|hearing|evidence|evidenceitem|motion|order|warrant|verdict|appeal>");
                yield true;
            }
        };
    }

    private boolean list(CommandSender sender, String[] args) {
        int page = page(sender, args);
        if (page < 1) return true;
        complete(sender, database.submit(() -> courts.list(PAGE_SIZE, (page - 1) * PAGE_SIZE)), found -> {
            messages.send(sender, "courts.list-header", Placeholder.unparsed("page", Integer.toString(page)));
            if (found.isEmpty()) messages.send(sender, "courts.list-empty");
            for (CourtCase courtCase : found) messages.send(sender, "courts.list-entry",
                    Placeholder.unparsed("id", Long.toString(courtCase.id())),
                    Placeholder.unparsed("number", courtCase.number()),
                    Placeholder.unparsed("title", courtCase.title()),
                    Placeholder.component("court", court(sender, courtCase.level().name())),
                    Placeholder.component("status", status(sender, courtCase.status().name())));
        });
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        Long id = id(sender, args, "/case info <id>");
        if (id == null) return true;
        complete(sender, database.submit(() -> courts.details(id)), details -> {
            if (details.isEmpty()) {
                returnNotFound(sender, id);
                return;
            }
            CourtCaseDetails view = details.get();
            CourtCase courtCase = view.courtCase();
            messages.send(sender, "courts.info-header",
                    Placeholder.unparsed("number", courtCase.number()),
                    Placeholder.unparsed("title", courtCase.title()));
            messages.send(sender, "courts.info-meta",
                    Placeholder.component("court", court(sender, courtCase.level().name())),
                    Placeholder.component("type", type(sender, courtCase.type().name())),
                    Placeholder.component("status", status(sender, courtCase.status().name())),
                    Placeholder.unparsed("plaintiff", courtCase.plaintiffName()),
                    Placeholder.unparsed("defendant", courtCase.defendantName()));
            messages.send(sender, "courts.info-claim", Placeholder.unparsed("claim", courtCase.claim()));
            if (courtCase.claimAmountCents() > 0) messages.send(sender, "courts.info-amount",
                    Placeholder.unparsed("amount", Money.format(courtCase.claimAmountCents(), currencySymbol)));
            view.judges().forEach(judge -> messages.send(sender, "courts.judge-entry",
                    Placeholder.unparsed("player", judge.playerName()),
                    Placeholder.component("role", role(sender, judge.role()))));
            if (courtCase.outcome() != null) messages.send(sender, "courts.info-decision",
                    Placeholder.component("outcome", outcome(sender, courtCase.outcome().name())),
                    Placeholder.unparsed("decision", courtCase.decision()),
                    Placeholder.unparsed("judgment", Money.format(courtCase.judgmentCents(), currencySymbol)),
                    Placeholder.unparsed("fine", Money.format(courtCase.fineCents(), currencySymbol)),
                    Placeholder.unparsed("jail", Integer.toString(courtCase.jailMinutes())));
            messages.send(sender, "courts.info-counts",
                    Placeholder.unparsed("evidence", Integer.toString(view.evidence().size())),
                    Placeholder.unparsed("orders", Integer.toString(view.orders().size())),
                    Placeholder.unparsed("warrants", Integer.toString(view.warrants().size())));
        });
        return true;
    }

    private boolean docket(CommandSender sender, String[] args) {
        Long id = id(sender, args, "/case docket <id>");
        if (id == null) return true;
        complete(sender, database.submit(() -> courts.details(id)), details -> {
            if (details.isEmpty()) {
                returnNotFound(sender, id);
                return;
            }
            messages.send(sender, "courts.docket-header",
                    Placeholder.unparsed("number", details.get().courtCase().number()));
            for (CourtDocketEntry entry : details.get().docket()) messages.send(sender, "courts.docket-entry",
                    Placeholder.unparsed("date", DATE.format(entry.createdAt())),
                    Placeholder.component("actor", entry.actorName() == null
                            ? messages.component(sender, "courts.system") : Component.text(entry.actorName())),
                    Placeholder.component("event", event(sender, entry.type())),
                    Placeholder.unparsed("text", entry.text() == null ? "" : entry.text()));
        });
        return true;
    }

    private boolean file(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length < 5) {
            usage(sender, "/case file <civil|criminal|constitutional|institutional> <player> [amount|minor|major] <title...> -- <claim...>");
            return true;
        }
        String kind = args[0].toLowerCase(Locale.ROOT);
        int divider = divider(args);
        int titleStart = kind.equals("civil") || kind.equals("criminal") ? 3 : 2;
        if (divider <= titleStart || divider == args.length - 1) {
            messages.send(sender, "courts.invalid-filing");
            return true;
        }
        String targetName = args[1];
        String title = String.join(" ", Arrays.copyOfRange(args, titleStart, divider));
        String claim = String.join(" ", Arrays.copyOfRange(args, divider + 1, args.length));
        long now = System.currentTimeMillis();
        complete(sender, database.submit(() -> {
            Optional<CitizenProfile> target = citizens.findByName(targetName);
            if (target.isEmpty()) return CourtOperation.result(CourtActionResult.PLAYER_NOT_FOUND);
            return switch (kind) {
                case "civil" -> courts.fileCivil(player.getUniqueId(), target.get().uuid(), title, claim,
                        Money.parsePositiveCents(args[2]), now);
                case "criminal" -> {
                    if (!args[2].equalsIgnoreCase("minor") && !args[2].equalsIgnoreCase("major"))
                        yield CourtOperation.result(CourtActionResult.INVALID_CONTENT);
                    yield courts.fileCriminal(player.getUniqueId(), target.get().uuid(),
                            args[2].equalsIgnoreCase("major"), title, claim, now);
                }
                case "constitutional" -> courts.fileConstitutional(
                        player.getUniqueId(), target.get().uuid(), title, claim, now);
                case "institutional" -> courts.fileInstitutional(
                        player.getUniqueId(), target.get().uuid(), title, claim, now);
                default -> CourtOperation.result(CourtActionResult.INVALID_CONTENT);
            };
        }), operation -> success(sender, operation, "courts.filed"));
        return true;
    }

    private boolean counsel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 2) return usageTrue(sender, "/case counsel <id> <player>");
        Long id = positiveLong(args[0]);
        if (id == null) return invalidId(sender);
        complete(sender, database.submit(() -> {
            Optional<CitizenProfile> target = citizens.findByName(args[1]);
            return target.isEmpty() ? CourtOperation.result(CourtActionResult.PLAYER_NOT_FOUND)
                    : courts.appointCounsel(id, player.getUniqueId(), target.get().uuid(), System.currentTimeMillis());
        }), operation -> success(sender, operation, "courts.counsel-set"));
        return true;
    }

    private boolean claim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        Long id = id(sender, args, "/case claim <id>");
        if (id == null) return true;
        operate(sender, () -> courts.claimBench(id, player.getUniqueId(), System.currentTimeMillis()), "courts.claimed");
        return true;
    }

    private boolean schedule(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 2) return usageTrue(sender, "/case schedule <id> <hours-from-now>");
        Long id = positiveLong(args[0]);
        Integer hours = integer(args[1], 1, 8760);
        if (id == null || hours == null) return invalidId(sender);
        long now = System.currentTimeMillis();
        operate(sender, () -> courts.schedule(id, player.getUniqueId(),
                now + Duration.ofHours(hours).toMillis(), now), "courts.scheduled");
        return true;
    }

    private boolean hearing(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        Long id = id(sender, args, "/case hearing <id>");
        if (id == null) return true;
        operate(sender, () -> courts.openHearing(id, player.getUniqueId(), System.currentTimeMillis()), "courts.hearing-opened");
        return true;
    }

    private boolean evidence(CommandSender sender, String[] args, boolean item) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length < 2) return usageTrue(sender, item
                ? "/case evidenceitem <id> <description...>" : "/case evidence <id> <description...>");
        Long id = positiveLong(args[0]);
        if (id == null) return invalidId(sender);
        byte[] data = null;
        if (item) {
            ItemStack held = player.getInventory().getItemInMainHand();
            if (held.getType() == Material.AIR) {
                messages.send(sender, "courts.hold-item");
                return true;
            }
            data = held.serializeAsBytes();
        }
        byte[] selected = data;
        String description = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        operate(sender, () -> courts.submitEvidence(id, player.getUniqueId(), description, selected,
                System.currentTimeMillis()), "courts.evidence-added");
        return true;
    }

    private boolean motion(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length < 2) return usageTrue(sender, "/case motion <id> <text...>");
        Long id = positiveLong(args[0]);
        if (id == null) return invalidId(sender);
        String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        operate(sender, () -> courts.motion(id, player.getUniqueId(), text, System.currentTimeMillis()), "courts.motion-filed");
        return true;
    }

    private boolean order(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        int divider = divider(args);
        if (args.length < 6 || divider < 4 || divider == args.length - 1)
            return usageTrue(sender, "/case order <id> <player|none> <type> <hours|permanent> -- <text...>");
        Long id = positiveLong(args[0]);
        if (id == null) return invalidId(sender);
        Integer orderHours = args[3].equalsIgnoreCase("permanent")
                ? null : integer(args[3], 1, 8_760);
        if (!args[3].equalsIgnoreCase("permanent") && orderHours == null) {
            messages.send(sender, "courts.error.invalid-content");
            return true;
        }
        String text = String.join(" ", Arrays.copyOfRange(args, divider + 1, args.length));
        long now = System.currentTimeMillis();
        complete(sender, database.submit(() -> {
            CitizenProfile target = args[1].equalsIgnoreCase("none") ? null : citizens.findByName(args[1]).orElse(null);
            if (!args[1].equalsIgnoreCase("none") && target == null)
                return CourtOperation.result(CourtActionResult.PLAYER_NOT_FOUND);
            Long expires = orderHours == null ? null : now + Duration.ofHours(orderHours).toMillis();
            return courts.issueOrder(id, player.getUniqueId(), target == null ? null : target.uuid(),
                    args[2], text, expires, now);
        }), operation -> success(sender, operation, "courts.order-issued"));
        return true;
    }

    private boolean warrant(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        int divider = divider(args);
        if (args.length < 6 || divider != 4) return usageTrue(sender,
                "/case warrant <id> <player> <arrest|search> <hours> -- <reason...>");
        Long id = positiveLong(args[0]);
        Integer hours = integer(args[3], 1, 720);
        WarrantType type;
        try { type = WarrantType.valueOf(args[2].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { messages.send(sender, "courts.invalid-warrant"); return true; }
        if (id == null || hours == null) return invalidId(sender);
        String reason = String.join(" ", Arrays.copyOfRange(args, 5, args.length));
        complete(sender, database.submit(() -> {
            Optional<CitizenProfile> target = citizens.findByName(args[1]);
            return target.isEmpty() ? CourtOperation.result(CourtActionResult.PLAYER_NOT_FOUND)
                    : courts.issueWarrant(id, player.getUniqueId(), target.get().uuid(), type, reason, hours,
                    System.currentTimeMillis());
        }), operation -> success(sender, operation, "courts.warrant-issued"));
        return true;
    }

    private boolean verdict(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        int divider = divider(args);
        if (args.length < 7 || divider != 5) return usageTrue(sender,
                "/case verdict <id> <outcome> <judgment> <fine> <jail-minutes> -- <reasoning...>");
        Long id = positiveLong(args[0]);
        CourtOutcome outcome;
        try { outcome = CourtOutcome.valueOf(args[1].toUpperCase(Locale.ROOT).replace('-', '_')); }
        catch (IllegalArgumentException exception) { messages.send(sender, "courts.invalid-outcome"); return true; }
        Integer jail = integer(args[4], 0, 100_000);
        if (id == null || jail == null) return invalidId(sender);
        String reasoning = String.join(" ", Arrays.copyOfRange(args, 6, args.length));
        try {
            long judgment = Money.parseCents(args[2]);
            long fine = Money.parseCents(args[3]);
            operate(sender, () -> courts.verdict(id, player.getUniqueId(), outcome,
                    judgment, fine, jail, reasoning, System.currentTimeMillis()), "courts.verdict-recorded");
        } catch (IllegalArgumentException exception) {
            messages.send(sender, "error.invalid-non-negative-amount");
        }
        return true;
    }

    private boolean appeal(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length < 3) return usageTrue(sender, "/case appeal <id> <law-error|unsupported-fact> <argument...>");
        Long id = positiveLong(args[0]);
        AppealGround ground;
        try { ground = AppealGround.valueOf(args[1].toUpperCase(Locale.ROOT).replace('-', '_')); }
        catch (IllegalArgumentException exception) { messages.send(sender, "courts.invalid-appeal"); return true; }
        if (id == null) return invalidId(sender);
        String argument = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        operate(sender, () -> courts.appeal(id, player.getUniqueId(), ground, argument,
                System.currentTimeMillis()), "courts.appealed");
        return true;
    }

    private boolean records(CommandSender sender, String[] args) {
        return profileList(sender, args, false);
    }

    private boolean warrants(CommandSender sender, String[] args) {
        return profileList(sender, args, true);
    }

    private boolean profileList(CommandSender sender, String[] args, boolean warrant) {
        if (args.length > 1 || args.length == 0 && !(sender instanceof Player)) return usageTrue(sender,
                warrant ? "/warrants [player]" : "/records [player]");
        complete(sender, database.submit(() -> args.length == 0
                ? citizens.find(((Player) sender).getUniqueId()) : citizens.findByName(args[0])), profile -> {
            if (profile.isEmpty()) { messages.send(sender, "error.player-not-found",
                    Placeholder.unparsed("player", args.length == 0 ? sender.getName() : args[0])); return; }
            if (warrant) complete(sender, database.submit(() -> courts.activeWarrants(
                    profile.get().uuid(), System.currentTimeMillis())), found -> {
                messages.send(sender, "courts.warrants-header", Placeholder.unparsed("player", profile.get().lastName()));
                if (found.isEmpty()) messages.send(sender, "courts.warrants-empty");
                found.forEach(entry -> messages.send(sender, "courts.warrant-entry",
                        Placeholder.unparsed("id", Long.toString(entry.id())),
                        Placeholder.component("type", warrantType(sender, entry.type().name())),
                        Placeholder.unparsed("reason", entry.reason()),
                        Placeholder.unparsed("expires", DATE.format(entry.expiresAt()))));
            }); else complete(sender, database.submit(() -> courts.criminalRecords(profile.get().uuid())), found -> {
                messages.send(sender, "courts.records-header", Placeholder.unparsed("player", profile.get().lastName()));
                if (found.isEmpty()) messages.send(sender, "courts.records-empty");
                found.forEach(entry -> messages.send(sender, "courts.record-entry",
                        Placeholder.unparsed("case", Long.toString(entry.caseId())),
                        Placeholder.unparsed("charge", entry.charge()),
                        Placeholder.unparsed("fine", Money.format(entry.fineCents(), currencySymbol)),
                        Placeholder.unparsed("jail", Integer.toString(entry.jailMinutes()))));
            });
        });
        return true;
    }

    private void operate(CommandSender sender, CourtCall call, String key) {
        complete(sender, database.submit(call::run), operation -> success(sender, operation, key));
    }

    private void success(CommandSender sender, CourtOperation operation, String key) {
        if (operation.result() == CourtActionResult.SUCCESS) messages.send(sender, key,
                Placeholder.unparsed("id", Long.toString(operation.courtCase().orElseThrow().id())),
                Placeholder.unparsed("number", operation.courtCase().orElseThrow().number()));
        else error(sender, operation.result());
    }

    private void error(CommandSender sender, CourtActionResult result) {
        messages.send(sender, "courts.error." + result.name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    private Component court(CommandSender sender, String value) { return messages.component(sender, "courts.level." + value.toLowerCase(Locale.ROOT)); }
    private Component type(CommandSender sender, String value) { return messages.component(sender, "courts.type." + value.toLowerCase(Locale.ROOT)); }
    private Component status(CommandSender sender, String value) { return messages.component(sender, "courts.status." + value.toLowerCase(Locale.ROOT)); }
    private Component outcome(CommandSender sender, String value) { return messages.component(sender, "courts.outcome." + value.toLowerCase(Locale.ROOT).replace('_', '-')); }
    private Component role(CommandSender sender, String value) { return messages.component(sender, "courts.role." + value.toLowerCase(Locale.ROOT).replace('_', '-')); }
    private Component event(CommandSender sender, String value) { return messages.component(sender, "courts.event." + value.toLowerCase(Locale.ROOT).replace('_', '-')); }
    private Component warrantType(CommandSender sender, String value) { return messages.component(sender, "courts.warrant-type." + value.toLowerCase(Locale.ROOT)); }

    private int page(CommandSender sender, String[] args) { if (args.length > 1) return -1; if (args.length == 0) return 1; Long page = positiveLong(args[0]); if (page == null || page > Integer.MAX_VALUE) { messages.send(sender, "error.invalid-page"); return -1; } return page.intValue(); }
    private Long id(CommandSender sender, String[] args, String syntax) { if (args.length != 1) { usage(sender, syntax); return null; } Long id = positiveLong(args[0]); if (id == null) messages.send(sender, "courts.invalid-id"); return id; }
    private static Long positiveLong(String value) { try { long parsed = Long.parseLong(value); return parsed > 0 ? parsed : null; } catch (NumberFormatException exception) { return null; } }
    private static Integer integer(String value, int min, int max) { try { int parsed = Integer.parseInt(value); return parsed >= min && parsed <= max ? parsed : null; } catch (NumberFormatException exception) { return null; } }
    private static int divider(String[] args) { for (int i = 0; i < args.length; i++) if (args[i].equals("--")) return i; return -1; }
    private boolean invalidId(CommandSender sender) { messages.send(sender, "courts.invalid-id"); return true; }
    private boolean usageTrue(CommandSender sender, String syntax) { usage(sender, syntax); return true; }
    private boolean playerOnly(CommandSender sender) { messages.send(sender, "error.player-only"); return true; }
    private void usage(CommandSender sender, String syntax) { messages.send(sender, "error.usage", Placeholder.unparsed("usage", syntax)); }
    private void returnNotFound(CommandSender sender, long id) { messages.send(sender, "courts.error.not-found", Placeholder.unparsed("id", Long.toString(id))); }
    private <T> void complete(CommandSender sender, CompletableFuture<T> future, Consumer<T> success) { future.whenComplete((value, error) -> Bukkit.getScheduler().runTask(plugin, () -> { if (error != null) { plugin.getLogger().log(Level.SEVERE, "An asynchronous court command failed", error); messages.send(sender, "error.database"); } else success.accept(value); })); }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase("case")) return List.of();
        if (args.length == 1) return filter(args[0], List.of("list", "info", "docket", "file", "counsel", "claim", "schedule", "hearing", "evidence", "evidenceitem", "motion", "order", "warrant", "verdict", "appeal"));
        if (args.length == 2 && args[0].equalsIgnoreCase("file")) return filter(args[1], List.of("civil", "criminal", "constitutional", "institutional"));
        if (args.length == 4 && args[0].equalsIgnoreCase("file") && args[1].equalsIgnoreCase("criminal")) return filter(args[3], List.of("minor", "major"));
        return List.of();
    }
    private static List<String> filter(String prefix, List<String> values) { String lower = prefix.toLowerCase(Locale.ROOT); return values.stream().filter(value -> value.startsWith(lower)).toList(); }

    @FunctionalInterface private interface CourtCall { CourtOperation run() throws Exception; }
}
