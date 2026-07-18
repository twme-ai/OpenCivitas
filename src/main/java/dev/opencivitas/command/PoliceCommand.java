package dev.opencivitas.command;

import dev.opencivitas.citizen.CitizenProfile;
import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.economy.Money;
import dev.opencivitas.message.MessageService;
import dev.opencivitas.police.ArrestStatus;
import dev.opencivitas.police.ChargeStatus;
import dev.opencivitas.police.CustodyService;
import dev.opencivitas.police.ForensicClue;
import dev.opencivitas.police.LawOperation;
import dev.opencivitas.police.LawResult;
import dev.opencivitas.police.Offense;
import dev.opencivitas.police.PoliceArrest;
import dev.opencivitas.police.PoliceCharge;
import dev.opencivitas.police.PolicePolicy;
import dev.opencivitas.police.PoliceReport;
import dev.opencivitas.police.PoliceReportDetails;
import dev.opencivitas.police.PoliceReportEvent;
import dev.opencivitas.police.PoliceRepository;
import dev.opencivitas.police.PublicCriminalRecord;
import dev.opencivitas.police.ReportStatus;
import dev.opencivitas.police.WantedCitizen;
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
import org.jetbrains.annotations.NotNull;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class PoliceCommand implements CommandExecutor, TabCompleter {
    private static final int PAGE_SIZE = 10;
    private static final Set<String> BUILT_IN_OFFENSES = Set.of(
            "assault", "attempted-murder", "murder", "robbery", "trespass");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final JavaPlugin plugin;
    private final Database database;
    private final CitizenRepository citizens;
    private final PoliceRepository police;
    private final PolicePolicy policy;
    private final CustodyService custody;
    private final MessageService messages;
    private final String currencySymbol;

    public PoliceCommand(
            JavaPlugin plugin,
            Database database,
            CitizenRepository citizens,
            PoliceRepository police,
            PolicePolicy policy,
            CustodyService custody,
            MessageService messages,
            String currencySymbol
    ) {
        this.plugin = plugin;
        this.database = database;
        this.citizens = citizens;
        this.police = police;
        this.policy = policy;
        this.custody = custody;
        this.messages = messages;
        this.currencySymbol = currencySymbol;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "911" -> emergency(sender, args);
            case "wanted" -> wanted(sender, args);
            case "records" -> records(sender, args);
            case "police" -> police(sender, args);
            default -> false;
        };
    }

    private boolean police(CommandSender sender, String[] args) {
        if (args.length == 0) {
            usage(sender, "/police <consent|reports|report|claim|dismiss|charge|charge-report|void|arrest|evidence|offenses|wanted|custody|setjail|setrelease>");
            return true;
        }
        String[] tail = Arrays.copyOfRange(args, 1, args.length);
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "consent" -> consent(sender, tail);
            case "reports" -> reports(sender, tail);
            case "report" -> report(sender, tail);
            case "claim" -> claim(sender, tail);
            case "dismiss" -> dismiss(sender, tail);
            case "charge" -> charge(sender, tail, false);
            case "charge-report" -> charge(sender, tail, true);
            case "void" -> voidCharge(sender, tail);
            case "arrest" -> arrest(sender, tail);
            case "evidence" -> evidence(sender, tail);
            case "offenses" -> offenses(sender, tail);
            case "wanted" -> wantedList(sender, tail);
            case "custody" -> custody(sender, tail);
            case "setjail" -> setLocation(sender, tail, true);
            case "setrelease" -> setLocation(sender, tail, false);
            default -> {
                usage(sender, "/police <consent|reports|report|claim|dismiss|charge|charge-report|void|arrest|evidence|offenses|wanted|custody|setjail|setrelease>");
                yield true;
            }
        };
    }

    private boolean emergency(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 0) return usageTrue(sender, "/911");
        complete(sender, database.submit(() -> {
            LawOperation<PoliceReport> operation = police.fileEmergencyReport(
                    player.getUniqueId(), System.currentTimeMillis());
            List<UUID> officers = operation.result() == LawResult.SUCCESS
                    ? police.lawEnforcementIds() : List.of();
            return new EmergencyResult(operation, officers);
        }), result -> {
            if (result.operation().result() != LawResult.SUCCESS) {
                error(sender, result.operation().result());
                return;
            }
            PoliceReport report = result.operation().value().orElseThrow();
            messages.send(sender, "police.report-filed",
                    Placeholder.unparsed("id", Long.toString(report.id())),
                    Placeholder.unparsed("suspect", report.suspectName()));
            for (UUID officerId : result.officers()) {
                Player officer = Bukkit.getPlayer(officerId);
                if (officer != null) messages.send(officer, "police.report-alert",
                        Placeholder.unparsed("id", Long.toString(report.id())),
                        Placeholder.unparsed("reporter", report.reporterName()),
                        Placeholder.unparsed("suspect", report.suspectName()));
            }
        });
        return true;
    }

    private boolean consent(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 0) return usageTrue(sender, "/police consent");
        complete(sender, database.submit(() -> police.toggleConsent(
                player.getUniqueId(), System.currentTimeMillis())), enabled ->
                messages.send(sender, enabled ? "police.consent-enabled" : "police.consent-disabled"));
        return true;
    }

    private boolean reports(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length > 2) return usageTrue(sender, "/police reports [all|open|claimed|charged|dismissed] [page]");
        ReportStatus status = null;
        int page = 1;
        if (args.length >= 1 && !args[0].equalsIgnoreCase("all")) {
            try {
                status = ReportStatus.valueOf(args[0].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                messages.send(sender, "police.invalid-report-status");
                return true;
            }
        }
        if (args.length == 2) {
            Integer parsed = positiveInt(args[1]);
            if (parsed == null) return invalidPage(sender);
            page = parsed;
        }
        int selectedPage = page;
        ReportStatus selectedStatus = status;
        complete(sender, database.submit(() -> police.reports(
                player.getUniqueId(), PAGE_SIZE, (selectedPage - 1) * PAGE_SIZE, selectedStatus)), operation -> {
            if (operation.result() != LawResult.SUCCESS) {
                error(sender, operation.result());
                return;
            }
            messages.send(sender, "police.reports-header",
                    Placeholder.unparsed("page", Integer.toString(selectedPage)));
            List<PoliceReport> found = operation.value().orElseThrow();
            if (found.isEmpty()) messages.send(sender, "police.reports-empty");
            for (PoliceReport report : found) messages.send(sender, "police.report-entry",
                    Placeholder.unparsed("id", Long.toString(report.id())),
                    Placeholder.unparsed("reporter", report.reporterName()),
                    Placeholder.unparsed("suspect", report.suspectName()),
                    Placeholder.component("status", reportStatus(sender, report.status())));
        });
        return true;
    }

    private boolean report(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        Long id = singleId(sender, args, "/police report <id>");
        if (id == null) return true;
        complete(sender, database.submit(() -> police.reportDetails(id, player.getUniqueId())), operation -> {
            if (operation.result() != LawResult.SUCCESS) {
                error(sender, operation.result());
                return;
            }
            PoliceReportDetails details = operation.value().orElseThrow();
            PoliceReport report = details.report();
            messages.send(sender, "police.report-header",
                    Placeholder.unparsed("id", Long.toString(report.id())),
                    Placeholder.component("status", reportStatus(sender, report.status())));
            messages.send(sender, "police.report-parties",
                    Placeholder.unparsed("reporter", report.reporterName()),
                    Placeholder.unparsed("suspect", report.suspectName()),
                    Placeholder.component("officer", report.assignedOfficerName() == null
                            ? messages.component(sender, "police.unassigned")
                            : Component.text(report.assignedOfficerName())));
            messages.send(sender, "police.report-incident",
                    Placeholder.unparsed("date", DATE.format(details.incident().deathAt())),
                    Placeholder.unparsed("world", details.incident().world()),
                    Placeholder.unparsed("x", Integer.toString((int) Math.floor(details.incident().x()))),
                    Placeholder.unparsed("y", Integer.toString((int) Math.floor(details.incident().y()))),
                    Placeholder.unparsed("z", Integer.toString((int) Math.floor(details.incident().z()))),
                    Placeholder.component("basis", legalBasis(sender, details.incident().legalBasis().name())));
            details.clue().ifPresent(clue -> messages.send(sender, "police.report-clue",
                    Placeholder.unparsed("id", Long.toString(clue.id())),
                    Placeholder.component("status", clueStatus(sender, clue.status()))));
            details.charge().ifPresent(charge -> messages.send(sender, "police.report-charge",
                    Placeholder.unparsed("id", Long.toString(charge.id())),
                    Placeholder.component("offense", offense(sender, charge.offenseId())),
                    Placeholder.component("status", chargeStatus(sender, charge.status().name()))));
            for (PoliceReportEvent event : details.events()) messages.send(sender, "police.report-event",
                    Placeholder.unparsed("date", DATE.format(event.createdAt())),
                    Placeholder.component("actor", event.actorName() == null
                            ? messages.component(sender, "police.system")
                            : Component.text(event.actorName())),
                    Placeholder.component("event", reportEvent(sender, event.type())),
                    Placeholder.unparsed("text", event.text() == null ? "" : event.text()));
        });
        return true;
    }

    private boolean claim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        Long id = singleId(sender, args, "/police claim <report-id>");
        if (id == null) return true;
        operate(sender, () -> police.claimReport(
                id, player.getUniqueId(), System.currentTimeMillis()), "police.report-claimed");
        return true;
    }

    private boolean dismiss(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length < 2) return usageTrue(sender, "/police dismiss <report-id> <reason...>");
        Long id = positiveLong(args[0]);
        if (id == null) return invalidId(sender);
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        operate(sender, () -> police.dismissReport(
                id, player.getUniqueId(), reason, System.currentTimeMillis()), "police.report-dismissed");
        return true;
    }

    private boolean charge(CommandSender sender, String[] args, boolean report) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        int divider = divider(args);
        if (args.length < 4 || divider != 2 || divider == args.length - 1) return usageTrue(sender,
                report ? "/police charge-report <report-id> <offense> -- <evidence...>"
                        : "/police charge <player> <offense> -- <evidence...>");
        Offense offense = policy.offense(args[1]).orElse(null);
        if (offense == null) {
            messages.send(sender, "police.error.offense-not-found");
            return true;
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        if (report) {
            Long reportId = positiveLong(args[0]);
            if (reportId == null) return invalidId(sender);
            operateCharge(sender, () -> police.chargeReport(
                    reportId, player.getUniqueId(), offense, reason, System.currentTimeMillis()));
        } else {
            complete(sender, database.submit(() -> {
                Optional<CitizenProfile> target = citizens.findByName(args[0]);
                return target.isEmpty() ? LawOperation.<PoliceCharge>failure(LawResult.PLAYER_NOT_FOUND)
                        : police.chargeCitizen(target.get().uuid(), player.getUniqueId(), offense,
                        reason, System.currentTimeMillis());
            }), operation -> chargeResult(sender, operation));
        }
        return true;
    }

    private boolean voidCharge(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        int divider = divider(args);
        if (args.length < 3 || divider != 1 || divider == args.length - 1) return usageTrue(sender,
                "/police void <charge-id> -- <reason...>");
        Long id = positiveLong(args[0]);
        if (id == null) return invalidId(sender);
        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        operateCharge(sender, () -> police.voidCharge(
                id, player.getUniqueId(), reason, System.currentTimeMillis()));
        return true;
    }

    private boolean arrest(CommandSender sender, String[] args) {
        if (!(sender instanceof Player officer)) return playerOnly(sender);
        if (args.length != 1) return usageTrue(sender, "/police arrest <player>");
        Player suspect = Bukkit.getPlayerExact(args[0]);
        if (suspect == null) {
            messages.send(sender, "error.player-not-found", Placeholder.unparsed("player", args[0]));
            return true;
        }
        if (suspect.equals(officer)) {
            messages.send(sender, "police.error.invalid-content");
            return true;
        }
        if (!officer.getWorld().equals(suspect.getWorld())
                || officer.getLocation().distanceSquared(suspect.getLocation())
                > policy.arrestDistance() * policy.arrestDistance()) {
            messages.send(sender, "police.arrest-too-far",
                    Placeholder.unparsed("distance", Double.toString(policy.arrestDistance())));
            return true;
        }
        if (custody.jail().isEmpty() || custody.release().isEmpty()) {
            messages.send(sender, "police.jail-not-configured");
            return true;
        }
        if (!custody.reserve(suspect.getUniqueId())) {
            messages.send(sender, "police.error.already-detained");
            return true;
        }
        complete(sender, database.submit(() -> police.arrest(
                officer.getUniqueId(), suspect.getUniqueId(), policy.warrantHoldMinutes(),
                policy.maximumCombinedJailMinutes(), System.currentTimeMillis())), operation -> {
            custody.cancelReservation(suspect.getUniqueId());
            if (operation.result() != LawResult.SUCCESS) {
                error(sender, operation.result());
                return;
            }
            PoliceArrest arrest = operation.value().orElseThrow();
            custody.activate(arrest);
            if (arrest.status() == ArrestStatus.ACTIVE && suspect.isOnline()) custody.confine(suspect);
            messages.send(sender, "police.arrested-officer",
                    Placeholder.unparsed("player", arrest.suspectName()),
                    Placeholder.unparsed("minutes", Integer.toString(arrest.jailMinutes())),
                    Placeholder.unparsed("fine", Money.format(arrest.fineCollectedCents(), currencySymbol)));
            if (suspect.isOnline()) messages.send(suspect, "police.arrested-suspect",
                    Placeholder.unparsed("officer", arrest.officerName()),
                    Placeholder.component("reason", arrestReason(suspect, arrest)),
                    Placeholder.unparsed("minutes", Integer.toString(arrest.jailMinutes())),
                    Placeholder.unparsed("fine", Money.format(arrest.fineCollectedCents(), currencySymbol)));
        });
        return true;
    }

    private boolean evidence(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        Long id = singleId(sender, args, "/police evidence <clue-id>");
        if (id == null) return true;
        complete(sender, database.submit(() -> police.clue(id, player.getUniqueId())), operation -> {
            if (operation.result() != LawResult.SUCCESS) {
                error(sender, operation.result());
                return;
            }
            ForensicClue clue = operation.value().orElseThrow();
            messages.send(sender, "police.evidence-header",
                    Placeholder.unparsed("id", Long.toString(clue.id())),
                    Placeholder.component("status", clueStatus(sender, clue.status())));
            messages.send(sender, "police.evidence-incident",
                    Placeholder.unparsed("attacker", clue.incident().attackerName()),
                    Placeholder.unparsed("victim", clue.incident().victimName()),
                    Placeholder.unparsed("date", DATE.format(clue.incident().deathAt())),
                    Placeholder.unparsed("cause", clue.incident().damageCause()),
                    Placeholder.unparsed("damage", String.format(Locale.ROOT, "%.2f",
                            clue.incident().damageMillihearts() / 1_000.0)));
        });
        return true;
    }

    private boolean offenses(CommandSender sender, String[] args) {
        if (args.length != 0) return usageTrue(sender, "/police offenses");
        messages.send(sender, "police.offenses-header");
        for (Offense offense : policy.offenses()) messages.send(sender, "police.offense-entry",
                Placeholder.component("offense", offense(sender, offense.id())),
                Placeholder.unparsed("id", offense.id()),
                Placeholder.unparsed("fine", Money.format(offense.fineCents(), currencySymbol)),
                Placeholder.unparsed("jail", Integer.toString(offense.jailMinutes())));
        return true;
    }

    private boolean wanted(CommandSender sender, String[] args) {
        if (args.length > 1 || args.length == 0 && !(sender instanceof Player)) {
            return usageTrue(sender, "/wanted [player]");
        }
        complete(sender, database.submit(() -> args.length == 0
                ? citizens.find(((Player) sender).getUniqueId()) : citizens.findByName(args[0])), profile -> {
            if (profile.isEmpty()) {
                messages.send(sender, "error.player-not-found",
                        Placeholder.unparsed("player", args.length == 0 ? sender.getName() : args[0]));
                return;
            }
            complete(sender, database.submit(() -> police.wanted(profile.get().uuid())), charges -> {
                messages.send(sender, "police.wanted-header",
                        Placeholder.unparsed("player", profile.get().lastName()));
                if (charges.isEmpty()) messages.send(sender, "police.wanted-clear");
                for (PoliceCharge charge : charges) messages.send(sender, "police.wanted-charge",
                        Placeholder.unparsed("id", Long.toString(charge.id())),
                        Placeholder.component("offense", offense(sender, charge.offenseId())),
                        Placeholder.unparsed("fine", Money.format(charge.fineCents(), currencySymbol)),
                        Placeholder.unparsed("jail", Integer.toString(charge.jailMinutes())),
                        Placeholder.unparsed("reason", charge.reason()));
            });
        });
        return true;
    }

    private boolean wantedList(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        int page = 1;
        if (args.length > 1) return usageTrue(sender, "/police wanted [page]");
        if (args.length == 1) {
            Integer parsed = positiveInt(args[0]);
            if (parsed == null) return invalidPage(sender);
            page = parsed;
        }
        int selectedPage = page;
        complete(sender, database.submit(() -> {
            if (!police.isLawEnforcement(player.getUniqueId())) return null;
            return police.wantedCitizens(PAGE_SIZE, (selectedPage - 1) * PAGE_SIZE);
        }), wanted -> {
            if (wanted == null) {
                error(sender, LawResult.NOT_AUTHORIZED);
                return;
            }
            messages.send(sender, "police.wanted-list-header",
                    Placeholder.unparsed("page", Integer.toString(selectedPage)));
            if (wanted.isEmpty()) messages.send(sender, "police.wanted-list-empty");
            for (WantedCitizen citizen : wanted) messages.send(sender, "police.wanted-list-entry",
                    Placeholder.unparsed("player", citizen.playerName()),
                    Placeholder.unparsed("charges", Integer.toString(citizen.openCharges())),
                    Placeholder.unparsed("fine", Money.format(citizen.totalFineCents(), currencySymbol)),
                    Placeholder.unparsed("jail", Integer.toString(citizen.totalJailMinutes())));
        });
        return true;
    }

    private boolean records(CommandSender sender, String[] args) {
        if (args.length > 1 || args.length == 0 && !(sender instanceof Player)) {
            return usageTrue(sender, "/records [player]");
        }
        complete(sender, database.submit(() -> args.length == 0
                ? citizens.find(((Player) sender).getUniqueId()) : citizens.findByName(args[0])), profile -> {
            if (profile.isEmpty()) {
                messages.send(sender, "error.player-not-found",
                        Placeholder.unparsed("player", args.length == 0 ? sender.getName() : args[0]));
                return;
            }
            complete(sender, database.submit(() -> police.criminalRecords(profile.get().uuid())), found -> {
                messages.send(sender, "police.records-header",
                        Placeholder.unparsed("player", profile.get().lastName()));
                if (found.isEmpty()) messages.send(sender, "police.records-empty");
                for (PublicCriminalRecord record : found) messages.send(sender, "police.record-entry",
                        Placeholder.unparsed("reference", record.reference()),
                        Placeholder.component("offense", offense(sender, record.charge())),
                        Placeholder.unparsed("fine", Money.format(record.fineCents(), currencySymbol)),
                        Placeholder.unparsed("jail", Integer.toString(record.jailMinutes())),
                        Placeholder.component("status", messages.component(sender,
                                record.cleared() ? "police.record-cleared" : "police.record-active")));
            });
        });
        return true;
    }

    private boolean custody(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 0) return usageTrue(sender, "/police custody");
        if (custody.detention(player.getUniqueId()).isEmpty()) messages.send(sender, "police.not-detained");
        else custody.sendRemaining(player);
        return true;
    }

    private boolean setLocation(CommandSender sender, String[] args, boolean jail) {
        if (!(sender instanceof Player player)) return playerOnly(sender);
        if (args.length != 0) return usageTrue(sender, jail ? "/police setjail" : "/police setrelease");
        if (!sender.hasPermission("opencivitas.police.configure")) {
            messages.send(sender, "error.no-permission");
            return true;
        }
        Location location = player.getLocation().clone();
        if (jail) custody.setJail(location);
        else custody.setRelease(location);
        messages.send(sender, jail ? "police.jail-set" : "police.release-set");
        return true;
    }

    private void operate(CommandSender sender, PoliceCall<PoliceReport> call, String key) {
        complete(sender, database.submit(call::run), operation -> {
            if (operation.result() != LawResult.SUCCESS) {
                error(sender, operation.result());
                return;
            }
            PoliceReport report = operation.value().orElseThrow();
            messages.send(sender, key, Placeholder.unparsed("id", Long.toString(report.id())));
        });
    }

    private void operateCharge(CommandSender sender, PoliceCall<PoliceCharge> call) {
        complete(sender, database.submit(call::run), operation -> chargeResult(sender, operation));
    }

    private void chargeResult(CommandSender sender, LawOperation<PoliceCharge> operation) {
        if (operation.result() != LawResult.SUCCESS) {
            error(sender, operation.result());
            return;
        }
        PoliceCharge charge = operation.value().orElseThrow();
        messages.send(sender, charge.status() == ChargeStatus.VOIDED
                        ? "police.charge-voided" : "police.charged",
                Placeholder.unparsed("id", Long.toString(charge.id())),
                Placeholder.unparsed("player", charge.suspectName()),
                Placeholder.component("offense", offense(sender, charge.offenseId())));
        Player suspect = Bukkit.getPlayerExact(charge.suspectName());
        if (suspect != null) messages.send(suspect,
                charge.status() == ChargeStatus.VOIDED
                        ? "police.charge-voided-notice" : "police.charge-notice",
                Placeholder.unparsed("id", Long.toString(charge.id())),
                Placeholder.component("offense", offense(suspect, charge.offenseId())),
                Placeholder.unparsed("reason", charge.reason()),
                Placeholder.unparsed("fine", Money.format(charge.fineCents(), currencySymbol)),
                Placeholder.unparsed("jail", Integer.toString(charge.jailMinutes())));
    }

    private Component offense(CommandSender sender, String id) {
        String normalized = id.toLowerCase(Locale.ROOT).replace('_', '-');
        if (BUILT_IN_OFFENSES.contains(normalized)) {
            return messages.component(sender, "police.offense." + normalized);
        }
        return Component.text(normalized.replace('-', ' '));
    }

    private Component arrestReason(CommandSender sender, PoliceArrest arrest) {
        List<Component> parts = new ArrayList<>();
        for (int index = 0; index < arrest.chargeIds().size(); index++) {
            parts.add(messages.component(sender, "police.arrest-charge-reason",
                    Placeholder.component("offense", offense(sender, arrest.offenseIds().get(index))),
                    Placeholder.unparsed("id", Long.toString(arrest.chargeIds().get(index)))));
        }
        if (!arrest.warrantIds().isEmpty()) parts.add(messages.component(
                sender, "police.arrest-warrant-reason",
                Placeholder.unparsed("count", Integer.toString(arrest.warrantIds().size()))));
        Component joined = Component.empty();
        for (int index = 0; index < parts.size(); index++) {
            if (index > 0) joined = joined.append(Component.text(", "));
            joined = joined.append(parts.get(index));
        }
        return joined;
    }

    private Component reportStatus(CommandSender sender, ReportStatus status) {
        return messages.component(sender, "police.report-status."
                + status.name().toLowerCase(Locale.ROOT));
    }

    private Component chargeStatus(CommandSender sender, String status) {
        return messages.component(sender, "police.charge-status." + status.toLowerCase(Locale.ROOT));
    }

    private Component clueStatus(CommandSender sender, String status) {
        return messages.component(sender, "police.clue-status." + status.toLowerCase(Locale.ROOT));
    }

    private Component legalBasis(CommandSender sender, String basis) {
        return messages.component(sender, "police.legal-basis."
                + basis.toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    private Component reportEvent(CommandSender sender, String event) {
        return messages.component(sender, "police.event."
                + event.toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    private void error(CommandSender sender, LawResult result) {
        messages.send(sender, "police.error."
                + result.name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    private Long singleId(CommandSender sender, String[] args, String syntax) {
        if (args.length != 1) {
            usage(sender, syntax);
            return null;
        }
        Long id = positiveLong(args[0]);
        if (id == null) messages.send(sender, "police.invalid-id");
        return id;
    }

    private static Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Integer positiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static int divider(String[] args) {
        for (int i = 0; i < args.length; i++) if (args[i].equals("--")) return i;
        return -1;
    }

    private boolean invalidId(CommandSender sender) {
        messages.send(sender, "police.invalid-id");
        return true;
    }

    private boolean invalidPage(CommandSender sender) {
        messages.send(sender, "error.invalid-page");
        return true;
    }

    private boolean usageTrue(CommandSender sender, String syntax) {
        usage(sender, syntax);
        return true;
    }

    private boolean playerOnly(CommandSender sender) {
        messages.send(sender, "error.player-only");
        return true;
    }

    private void usage(CommandSender sender, String syntax) {
        messages.send(sender, "error.usage", Placeholder.unparsed("usage", syntax));
    }

    private <T> void complete(
            CommandSender sender, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((value, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "An asynchronous police command failed", error);
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
        if (!command.getName().equalsIgnoreCase("police")) return List.of();
        if (args.length == 1) return filter(args[0], List.of(
                "consent", "reports", "report", "claim", "dismiss", "charge", "charge-report",
                "void", "arrest", "evidence", "offenses", "wanted", "custody", "setjail", "setrelease"));
        if (args.length == 2 && args[0].equalsIgnoreCase("reports")) return filter(
                args[1], List.of("all", "open", "claimed", "charged", "dismissed"));
        if (args.length == 2 && (args[0].equalsIgnoreCase("charge")
                || args[0].equalsIgnoreCase("arrest"))) return onlinePlayers(args[1]);
        if (args.length == 3 && (args[0].equalsIgnoreCase("charge")
                || args[0].equalsIgnoreCase("charge-report"))) return filter(
                args[2], policy.offenses().stream().map(Offense::id).toList());
        return List.of();
    }

    private static List<String> onlinePlayers(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }

    private static List<String> filter(String prefix, List<String> values) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(lower)).toList();
    }

    private record EmergencyResult(
            LawOperation<PoliceReport> operation, List<UUID> officers) {
        private EmergencyResult {
            officers = List.copyOf(officers);
        }
    }

    @FunctionalInterface
    private interface PoliceCall<T> {
        LawOperation<T> run() throws Exception;
    }
}
