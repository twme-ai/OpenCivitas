package dev.opencivitas.command;

import dev.opencivitas.citizen.CitizenProfile;
import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.job.CitizenJob;
import dev.opencivitas.job.CitizenLicense;
import dev.opencivitas.job.JobCategory;
import dev.opencivitas.job.JobDefinition;
import dev.opencivitas.job.JobJoinResult;
import dev.opencivitas.job.JobRegistry;
import dev.opencivitas.job.JobRepository;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class JobCommand implements CommandExecutor, TabCompleter {
    private static final Pattern QUALIFICATION_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");

    private final JavaPlugin plugin;
    private final Database database;
    private final CitizenRepository citizens;
    private final JobRepository jobs;
    private final JobRegistry registry;
    private final MessageService messages;

    public JobCommand(
            JavaPlugin plugin,
            Database database,
            CitizenRepository citizens,
            JobRepository jobs,
            JobRegistry registry,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.citizens = citizens;
        this.jobs = jobs;
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
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "jobs" -> viewJobs(sender, args);
            case "job" -> job(sender, args);
            case "qualifications" -> viewQualifications(sender, args);
            case "qualification" -> qualification(sender, args);
            case "licenses" -> viewLicenses(sender, args);
            case "license" -> license(sender, args);
            case "setprefix" -> setPrefix(sender, args);
            case "quitjob" -> quitJob(sender, args);
            case "quitprofession" -> quitProfession(sender, args);
            default -> false;
        };
    }

    private boolean viewJobs(CommandSender sender, String[] args) {
        if (!validateProfileLookup(sender, args, "/jobs [player]")) {
            return true;
        }
        complete(sender, database.submit(() -> {
            Optional<CitizenProfile> profile = profile(sender, args);
            return new JobsView(
                    profile,
                    profile.isEmpty() ? List.of() : jobs.jobs(profile.get().uuid()),
                    profile.isEmpty() ? Optional.empty() : jobs.prefix(profile.get().uuid()));
        }), view -> {
            if (view.profile().isEmpty()) {
                notFound(sender, args);
                return;
            }
            messages.send(sender, "jobs.header",
                    Placeholder.unparsed("player", view.profile().get().lastName()));
            view.prefix().ifPresent(prefix -> messages.send(sender, "jobs.prefix-current",
                    Placeholder.unparsed("job", displayName(prefix))));
            if (view.jobs().isEmpty()) {
                messages.send(sender, "jobs.empty");
                return;
            }
            for (CitizenJob job : view.jobs()) {
                messages.send(sender, "jobs.entry",
                        Placeholder.unparsed("job", displayName(job.id())),
                        Placeholder.component("category", category(sender, job.category())));
            }
        });
        return true;
    }

    private boolean viewQualifications(CommandSender sender, String[] args) {
        if (!validateProfileLookup(sender, args, "/qualifications [player]")) {
            return true;
        }
        complete(sender, database.submit(() -> {
            Optional<CitizenProfile> profile = profile(sender, args);
            return new QualificationsView(
                    profile,
                    profile.isEmpty() ? List.of() : jobs.qualifications(profile.get().uuid())
            );
        }), view -> {
            if (view.profile().isEmpty()) {
                notFound(sender, args);
                return;
            }
            messages.send(sender, "qualifications.header",
                    Placeholder.unparsed("player", view.profile().get().lastName()));
            if (view.qualifications().isEmpty()) {
                messages.send(sender, "qualifications.empty");
                return;
            }
            for (String qualification : view.qualifications()) {
                messages.send(sender, "qualifications.entry",
                        Placeholder.unparsed("qualification", displayName(qualification)));
            }
        });
        return true;
    }

    private boolean viewLicenses(CommandSender sender, String[] args) {
        if (!validateProfileLookup(sender, args, "/licenses [player]")) return true;
        complete(sender, database.submit(() -> {
            Optional<CitizenProfile> profile = profile(sender, args);
            return new LicensesView(profile, profile.isEmpty()
                    ? List.of() : jobs.licenses(profile.get().uuid(), System.currentTimeMillis()));
        }), view -> {
            if (view.profile().isEmpty()) {
                notFound(sender, args);
                return;
            }
            messages.send(sender, "licenses.header",
                    Placeholder.unparsed("player", view.profile().get().lastName()));
            if (view.licenses().isEmpty()) messages.send(sender, "licenses.empty");
            for (CitizenLicense license : view.licenses()) {
                messages.send(sender, license.expiresAt() == null
                                ? "licenses.entry-permanent" : "licenses.entry-expiring",
                        Placeholder.unparsed("license", displayName(license.id())),
                        Placeholder.unparsed("expires", license.expiresAt() == null ? "" : license.expiresAt().toString()));
            }
        });
        return true;
    }

    private boolean license(CommandSender sender, String[] args) {
        if (!sender.hasPermission("opencivitas.licenses.manage")) {
            messages.send(sender, "error.no-permission");
            return true;
        }
        if (args.length < 3 || args.length > 4
                || (!args[0].equalsIgnoreCase("grant") && !args[0].equalsIgnoreCase("revoke"))) {
            usage(sender, "/license <grant|revoke> <player> <license> [days|permanent]");
            return true;
        }
        String license = args[2].toLowerCase(Locale.ROOT);
        if (!QUALIFICATION_ID.matcher(license).matches()) {
            messages.send(sender, "licenses.invalid");
            return true;
        }
        boolean grant = args[0].equalsIgnoreCase("grant");
        if (!grant && args.length != 3) {
            usage(sender, "/license revoke <player> <license>");
            return true;
        }
        long now = System.currentTimeMillis();
        Long expires = null;
        if (grant && args.length == 4 && !args[3].equalsIgnoreCase("permanent")) {
            try {
                int days = Integer.parseInt(args[3]);
                if (days < 1 || days > 3650) throw new NumberFormatException("Days outside range");
                expires = Math.addExact(now, Duration.ofDays(days).toMillis());
            } catch (NumberFormatException | ArithmeticException exception) {
                messages.send(sender, "licenses.invalid-days");
                return true;
            }
        }
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        Long selectedExpiry = expires;
        complete(sender, database.submit(() -> {
            Optional<CitizenProfile> target = citizens.findByName(args[1]);
            boolean changed = target.isPresent() && (grant
                    ? jobs.grantLicense(target.get().uuid(), license, actor, selectedExpiry, now)
                    : jobs.revokeLicense(target.get().uuid(), license));
            return new LicenseChange(target, changed);
        }), outcome -> {
            if (outcome.profile().isEmpty()) {
                notFound(sender, new String[]{args[1]});
                return;
            }
            messages.send(sender, outcome.changed()
                            ? grant ? "licenses.granted" : "licenses.revoked"
                            : "licenses.not-held",
                    Placeholder.unparsed("player", outcome.profile().get().lastName()),
                    Placeholder.unparsed("license", displayName(license)));
        });
        return true;
    }

    private boolean setPrefix(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 1) {
            usage(sender, "/setprefix <job|clear>");
            return true;
        }
        String selected = args[0].equalsIgnoreCase("clear") ? null : args[0].toLowerCase(Locale.ROOT);
        if (selected != null && registry.find(selected).isEmpty()) {
            unknownJob(sender, selected);
            return true;
        }
        complete(sender, database.submit(() -> jobs.setPrefix(
                player.getUniqueId(), selected, System.currentTimeMillis())), changed -> {
            if (!changed) {
                messages.send(sender, "jobs.not-held", Placeholder.unparsed("job", displayName(selected)));
            } else if (selected == null) {
                messages.send(sender, "jobs.prefix-cleared");
            } else {
                messages.send(sender, "jobs.prefix-set", Placeholder.unparsed("job", displayName(selected)));
            }
        });
        return true;
    }

    private boolean job(CommandSender sender, String[] args) {
        if (args.length == 0) {
            usage(sender, "/job <list|join|quit|employ|fire>");
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> listJobs(sender, args);
            case "join" -> join(sender, args);
            case "quit" -> quitJob(sender, tail(args));
            case "employ" -> employ(sender, args);
            case "fire" -> fire(sender, args);
            default -> {
                usage(sender, "/job <list|join|quit|employ|fire>");
                yield true;
            }
        };
    }

    private boolean listJobs(CommandSender sender, String[] args) {
        if (args.length != 1) {
            usage(sender, "/job list");
            return true;
        }
        messages.send(sender, "jobs.available-header");
        for (JobDefinition job : registry.all()) {
            Component status = messages.component(sender,
                    job.selfJoin() ? "jobs.self-join" : "jobs.appointment");
            messages.send(sender, "jobs.available-entry",
                    Placeholder.unparsed("job", displayName(job.id())),
                    Placeholder.component("category", category(sender, job.category())),
                    Placeholder.component("status", status));
        }
        return true;
    }

    private boolean join(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 2) {
            usage(sender, "/job join <job>");
            return true;
        }
        Optional<JobDefinition> selected = registry.find(args[1]);
        if (selected.isEmpty()) {
            unknownJob(sender, args[1]);
            return true;
        }
        JobDefinition job = selected.get();
        if (!job.selfJoin()) {
            messages.send(sender, "jobs.appointment-only",
                    Placeholder.unparsed("job", displayName(job.id())));
            return true;
        }
        complete(sender,
                database.submit(() -> jobs.join(player.getUniqueId(), job, registry.limit(job.category()))),
                result -> showJoinResult(sender, job, result));
        return true;
    }

    private void showJoinResult(CommandSender sender, JobDefinition job, JobJoinResult result) {
        switch (result) {
            case SUCCESS -> messages.send(sender, "jobs.joined",
                    Placeholder.unparsed("job", displayName(job.id())));
            case ALREADY_JOINED -> messages.send(sender, "jobs.already-held",
                    Placeholder.unparsed("job", displayName(job.id())));
            case MISSING_QUALIFICATION -> messages.send(sender, "jobs.missing-qualification",
                    Placeholder.unparsed("qualification", displayName(job.qualification())));
            case CATEGORY_LIMIT -> messages.send(sender, "jobs.limit",
                    Placeholder.component("category", category(sender, job.category())));
            case CITIZEN_NOT_FOUND -> messages.send(sender, "error.database");
        }
    }

    private boolean quitJob(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 1) {
            usage(sender, "/quitjob <job>");
            return true;
        }
        Optional<JobDefinition> selected = registry.find(args[0]);
        if (selected.isEmpty()) {
            unknownJob(sender, args[0]);
            return true;
        }
        JobDefinition job = selected.get();
        complete(sender, database.submit(() -> jobs.leave(player.getUniqueId(), job.id())), removed -> {
            messages.send(sender, removed ? "jobs.left" : "jobs.not-held",
                    Placeholder.unparsed("job", displayName(job.id())));
        });
        return true;
    }

    private boolean quitProfession(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length != 0) {
            usage(sender, "/quitprofession");
            return true;
        }
        complete(sender,
                database.submit(() -> jobs.leaveCategory(player.getUniqueId(), JobCategory.PROFESSION)),
                removed -> messages.send(sender,
                        removed > 0 ? "jobs.profession-left" : "jobs.no-profession"));
        return true;
    }

    private boolean employ(CommandSender sender, String[] args) {
        if (!sender.hasPermission("opencivitas.jobs.manage")) {
            messages.send(sender, "error.no-permission");
            return true;
        }
        if (args.length != 3) {
            usage(sender, "/job employ <player> <job>");
            return true;
        }
        Optional<JobDefinition> selected = registry.find(args[2]);
        if (selected.isEmpty()) {
            unknownJob(sender, args[2]);
            return true;
        }
        JobDefinition job = selected.get();
        UUID appointer = sender instanceof Player player ? player.getUniqueId() : null;
        complete(sender, database.submit(() -> {
            Optional<CitizenProfile> target = citizens.findByName(args[1]);
            if (target.isEmpty()) {
                return new EmploymentResult(Optional.empty(), JobJoinResult.CITIZEN_NOT_FOUND);
            }
            JobJoinResult result = jobs.appoint(
                    target.get().uuid(), job, registry.limit(job.category()), appointer);
            return new EmploymentResult(target, result);
        }), outcome -> {
            if (outcome.profile().isEmpty()) {
                notFound(sender, new String[]{args[1]});
                return;
            }
            if (outcome.result() == JobJoinResult.SUCCESS) {
                messages.send(sender, "jobs.employed",
                        Placeholder.unparsed("player", outcome.profile().get().lastName()),
                        Placeholder.unparsed("job", displayName(job.id())));
            } else {
                showJoinResult(sender, job, outcome.result());
            }
        });
        return true;
    }

    private boolean fire(CommandSender sender, String[] args) {
        if (!sender.hasPermission("opencivitas.jobs.manage")) {
            messages.send(sender, "error.no-permission");
            return true;
        }
        if (args.length != 3) {
            usage(sender, "/job fire <player> <job>");
            return true;
        }
        Optional<JobDefinition> selected = registry.find(args[2]);
        if (selected.isEmpty()) {
            unknownJob(sender, args[2]);
            return true;
        }
        JobDefinition job = selected.get();
        complete(sender, database.submit(() -> {
            Optional<CitizenProfile> target = citizens.findByName(args[1]);
            boolean removed = target.isPresent() && jobs.leave(target.get().uuid(), job.id());
            return new RemovalResult(target, removed);
        }), outcome -> {
            if (outcome.profile().isEmpty()) {
                notFound(sender, new String[]{args[1]});
                return;
            }
            messages.send(sender, outcome.removed() ? "jobs.fired" : "jobs.not-held",
                    Placeholder.unparsed("player", outcome.profile().get().lastName()),
                    Placeholder.unparsed("job", displayName(job.id())));
        });
        return true;
    }

    private boolean qualification(CommandSender sender, String[] args) {
        if (!sender.hasPermission("opencivitas.qualifications.manage")) {
            messages.send(sender, "error.no-permission");
            return true;
        }
        if (args.length != 3 || (!args[0].equalsIgnoreCase("grant")
                && !args[0].equalsIgnoreCase("revoke"))) {
            usage(sender, "/qualification <grant|revoke> <player> <qualification>");
            return true;
        }
        String qualification = args[2].toLowerCase(Locale.ROOT);
        if (!QUALIFICATION_ID.matcher(qualification).matches()) {
            messages.send(sender, "qualifications.invalid");
            return true;
        }
        boolean grant = args[0].equalsIgnoreCase("grant");
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        complete(sender, database.submit(() -> {
            Optional<CitizenProfile> target = citizens.findByName(args[1]);
            boolean changed = target.isPresent() && (grant
                    ? jobs.grantQualification(target.get().uuid(), qualification, actor)
                    : jobs.revokeQualification(target.get().uuid(), qualification));
            return new QualificationChange(target, changed);
        }), outcome -> {
            if (outcome.profile().isEmpty()) {
                notFound(sender, new String[]{args[1]});
                return;
            }
            String key;
            if (outcome.changed()) {
                key = grant ? "qualifications.granted" : "qualifications.revoked";
            } else {
                key = grant ? "qualifications.already-held" : "qualifications.not-held";
            }
            messages.send(sender, key,
                    Placeholder.unparsed("player", outcome.profile().get().lastName()),
                    Placeholder.unparsed("qualification", displayName(qualification)));
        });
        return true;
    }

    private boolean validateProfileLookup(CommandSender sender, String[] args, String usage) {
        if (args.length > 1) {
            usage(sender, usage);
            return false;
        }
        if (args.length == 0 && !(sender instanceof Player)) {
            messages.send(sender, "error.player-only");
            return false;
        }
        if (args.length == 1 && !sender.hasPermission("opencivitas.jobs.others")) {
            messages.send(sender, "error.no-permission");
            return false;
        }
        return true;
    }

    private Optional<CitizenProfile> profile(CommandSender sender, String[] args) throws java.sql.SQLException {
        return args.length == 0
                ? citizens.find(((Player) sender).getUniqueId())
                : citizens.findByName(args[0]);
    }

    private Component category(CommandSender sender, JobCategory category) {
        return messages.component(sender, "jobs.category." + category.name().toLowerCase(Locale.ROOT));
    }

    private void notFound(CommandSender sender, String[] args) {
        String requested = args.length == 0 ? sender.getName() : args[0];
        messages.send(sender, "error.player-not-found", Placeholder.unparsed("player", requested));
    }

    private void unknownJob(CommandSender sender, String id) {
        messages.send(sender, "jobs.unknown", Placeholder.unparsed("job", id));
    }

    private void usage(CommandSender sender, String usage) {
        messages.send(sender, "error.usage", Placeholder.unparsed("usage", usage));
    }

    private <T> void complete(CommandSender sender, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "An asynchronous job operation failed", error);
                messages.send(sender, "error.database");
                return;
            }
            success.accept(result);
        }));
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        List<String> jobIds = registry.all().stream().map(JobDefinition::id).toList();
        if ((name.equals("jobs") || name.equals("qualifications") || name.equals("licenses")) && args.length == 1) {
            return filter(args[0], onlineNames());
        }
        if (name.equals("quitjob") && args.length == 1) {
            return filter(args[0], jobIds);
        }
        if (name.equals("job")) {
            if (args.length == 1) {
                return filter(args[0], List.of("list", "join", "quit", "employ", "fire"));
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("join")
                    || args[0].equalsIgnoreCase("quit"))) {
                return filter(args[1], jobIds);
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("employ")
                    || args[0].equalsIgnoreCase("fire"))) {
                return filter(args[1], onlineNames());
            }
            if (args.length == 3 && (args[0].equalsIgnoreCase("employ")
                    || args[0].equalsIgnoreCase("fire"))) {
                return filter(args[2], jobIds);
            }
        }
        if (name.equals("qualification")) {
            if (args.length == 1) {
                return filter(args[0], List.of("grant", "revoke"));
            }
            if (args.length == 2) {
                return filter(args[1], onlineNames());
            }
            if (args.length == 3) {
                return filter(args[2], registry.all().stream().map(JobDefinition::qualification).distinct().toList());
            }
        }
        if (name.equals("license")) {
            if (args.length == 1) return filter(args[0], List.of("grant", "revoke"));
            if (args.length == 2) return filter(args[1], onlineNames());
        }
        if (name.equals("setprefix") && args.length == 1) {
            List<String> values = new ArrayList<>(jobIds);
            values.add("clear");
            return filter(args[0], values);
        }
        return List.of();
    }

    private static List<String> onlineNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private static List<String> filter(String input, Collection<String> candidates) {
        String prefix = input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(candidate);
            }
        }
        return matches;
    }

    private static String displayName(String id) {
        String[] words = id.split("-");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private static String[] tail(String[] args) {
        String[] tail = new String[args.length - 1];
        System.arraycopy(args, 1, tail, 0, tail.length);
        return tail;
    }

    private record JobsView(
            Optional<CitizenProfile> profile,
            List<CitizenJob> jobs,
            Optional<String> prefix
    ) {
    }

    private record QualificationsView(Optional<CitizenProfile> profile, List<String> qualifications) {
    }

    private record LicensesView(Optional<CitizenProfile> profile, List<CitizenLicense> licenses) {
    }

    private record EmploymentResult(Optional<CitizenProfile> profile, JobJoinResult result) {
    }

    private record RemovalResult(Optional<CitizenProfile> profile, boolean removed) {
    }

    private record QualificationChange(Optional<CitizenProfile> profile, boolean changed) {
    }

    private record LicenseChange(Optional<CitizenProfile> profile, boolean changed) {
    }
}
