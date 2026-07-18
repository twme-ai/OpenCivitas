package dev.opencivitas.command;

import dev.opencivitas.database.Database;
import dev.opencivitas.exam.ExamAnswer;
import dev.opencivitas.exam.ExamDefinition;
import dev.opencivitas.exam.ExamQuestion;
import dev.opencivitas.exam.ExamRegistry;
import dev.opencivitas.exam.ExamRepository;
import dev.opencivitas.exam.ExamSession;
import dev.opencivitas.exam.LocalizedText;
import dev.opencivitas.exam.UniversityService;
import dev.opencivitas.message.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public final class ExamCommand implements CommandExecutor, TabCompleter, Listener {
    private final JavaPlugin plugin;
    private final Database database;
    private final ExamRepository repository;
    private final ExamRegistry registry;
    private final UniversityService university;
    private final MessageService messages;
    private final Map<UUID, ExamSession> sessions = new HashMap<>();

    public ExamCommand(
            JavaPlugin plugin,
            Database database,
            ExamRepository repository,
            ExamRegistry registry,
            UniversityService university,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.database = database;
        this.repository = repository;
        this.registry = registry;
        this.university = university;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (command.getName().equalsIgnoreCase("university")) {
            return university(sender, args);
        }
        return exams(sender, args);
    }

    private boolean exams(CommandSender sender, String[] args) {
        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("list"))) {
            list(sender);
            return true;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length == 1 && args[0].length() == 1 && Character.isLetter(args[0].charAt(0))) {
            answer(player, args[0].charAt(0));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("answer") && args[1].length() == 1) {
            answer(player, args[1].charAt(0));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            start(player, args[1]);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("quit")) {
            ExamSession removed = sessions.remove(player.getUniqueId());
            messages.send(player, removed == null ? "exams.none-active" : "exams.quit");
            return true;
        }
        usage(sender, "/exams <list|start <exam>|answer <letter>|quit>");
        return true;
    }

    private void list(CommandSender sender) {
        messages.send(sender, "exams.list-header");
        for (ExamDefinition exam : registry.all()) {
            messages.send(sender, "exams.list-entry",
                    Placeholder.unparsed("exam", displayName(exam.id())),
                    Placeholder.unparsed("qualification", displayName(exam.qualification())));
        }
    }

    private void start(Player player, String examId) {
        Optional<ExamDefinition> selected = registry.find(examId);
        if (selected.isEmpty()) {
            messages.send(player, "exams.unknown", Placeholder.unparsed("exam", examId));
            return;
        }
        if (sessions.containsKey(player.getUniqueId())) {
            messages.send(player, "exams.active");
            return;
        }
        ExamSession session = new ExamSession(selected.get(), ThreadLocalRandom.current());
        sessions.put(player.getUniqueId(), session);
        String locale = messages.locale(player);
        Component title = messages.parse(session.definition().title().resolve(locale, messages.defaultLocale()));
        Component description = messages.parse(
                session.definition().description().resolve(locale, messages.defaultLocale()));
        messages.send(player, "exams.title", Placeholder.component("title", title));
        messages.send(player, "exams.description", Placeholder.component("description", description));
        showQuestion(player, session);
    }

    private void answer(Player player, char option) {
        ExamSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            messages.send(player, "exams.none-active");
            return;
        }
        ExamAnswer result = session.answer(option);
        if (result.status() == ExamAnswer.Status.INVALID_OPTION) {
            messages.send(player, "exams.invalid-option");
            return;
        }
        if (result.status() == ExamAnswer.Status.NEXT_QUESTION) {
            showQuestion(player, session);
            return;
        }
        sessions.remove(player.getUniqueId());
        finish(player, session);
    }

    private void finish(Player player, ExamSession session) {
        ExamDefinition exam = session.definition();
        database.submit(() -> repository.record(
                player.getUniqueId(),
                exam.id(),
                exam.qualification(),
                session.score(),
                session.totalQuestions(),
                session.passed()
        )).whenComplete((record, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not record exam completion", error);
                messages.send(player, "error.database");
                return;
            }
            if (session.passed()) {
                messages.send(player, "exams.passed",
                        Placeholder.unparsed("score", Integer.toString(session.score())),
                        Placeholder.unparsed("total", Integer.toString(session.totalQuestions())),
                        Placeholder.unparsed("qualification", displayName(exam.qualification())));
                if (!record.qualificationGranted()) {
                    messages.send(player, "exams.already-qualified",
                            Placeholder.unparsed("qualification", displayName(exam.qualification())));
                }
            } else {
                messages.send(player, "exams.failed",
                        Placeholder.unparsed("score", Integer.toString(session.score())),
                        Placeholder.unparsed("total", Integer.toString(session.totalQuestions())),
                        Placeholder.unparsed("required", Integer.toString(exam.passingScore())));
            }
        }));
    }

    private void showQuestion(Player player, ExamSession session) {
        String locale = messages.locale(player);
        ExamQuestion question = session.currentQuestion();
        Component prompt = parse(question.prompt(), locale);
        messages.send(player, "exams.question",
                Placeholder.unparsed("number", Integer.toString(session.questionNumber())),
                Placeholder.unparsed("total", Integer.toString(session.totalQuestions())),
                Placeholder.component("prompt", prompt));
        for (Map.Entry<Character, LocalizedText> option : question.options().entrySet()) {
            messages.send(player, "exams.option",
                    Placeholder.unparsed("letter", Character.toString(option.getKey())),
                    Placeholder.component("answer", parse(option.getValue(), locale)));
        }
    }

    private Component parse(LocalizedText text, String locale) {
        return messages.parse(text.resolve(locale, messages.defaultLocale()));
    }

    private boolean university(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.player-only");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("setwarp")) {
            if (!sender.hasPermission("opencivitas.university.setwarp")) {
                messages.send(sender, "error.no-permission");
                return true;
            }
            university.setWarp(player.getLocation());
            messages.send(sender, "university.set");
            return true;
        }
        if (args.length != 0) {
            usage(sender, "/university [setwarp]");
            return true;
        }
        Optional<Location> warp = university.warp();
        if (warp.isEmpty()) {
            messages.send(sender, "university.not-set");
            return true;
        }
        player.teleportAsync(warp.get(), PlayerTeleportEvent.TeleportCause.COMMAND)
                .whenComplete((success, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error == null && Boolean.TRUE.equals(success)) {
                        messages.send(player, "university.arrived");
                    } else {
                        messages.send(player, "university.teleport-failed");
                    }
                }));
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (command.getName().equalsIgnoreCase("university")) {
            return args.length == 1 && sender.hasPermission("opencivitas.university.setwarp")
                    ? filter(args[0], List.of("setwarp")) : List.of();
        }
        if (args.length == 1) {
            return filter(args[0], List.of("list", "start", "answer", "quit"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            return filter(args[1], registry.all().stream().map(ExamDefinition::id).toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("answer")) {
            return filter(args[1], List.of("A", "B", "C", "D"));
        }
        return List.of();
    }

    private void usage(CommandSender sender, String usage) {
        messages.send(sender, "error.usage", Placeholder.unparsed("usage", usage));
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
}
