package dev.opencivitas.exam;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class ExamRegistry {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");

    private final Map<String, ExamDefinition> exams;

    public ExamRegistry(JavaPlugin plugin, String defaultLocale) {
        File file = new File(plugin.getDataFolder(), "exams.yml");
        if (!file.exists()) {
            plugin.saveResource("exams.yml", false);
        }
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = configuration.getConfigurationSection("exams");
        if (section == null) {
            throw new IllegalArgumentException("exams.yml does not contain an exams section");
        }
        Map<String, ExamDefinition> loaded = new LinkedHashMap<>();
        for (String rawId : section.getKeys(false)) {
            String id = normalizeId(rawId, "exam");
            ConfigurationSection exam = requiredSection(section, rawId);
            String qualification = normalizeId(exam.getString("qualification", id), "qualification");
            LocalizedText title = localized(exam, "title", defaultLocale);
            LocalizedText description = localized(exam, "description", defaultLocale);
            List<ExamQuestion> questions = questions(exam, defaultLocale, id);
            int passingScore = exam.getInt("passing-score", questions.size());
            boolean randomize = exam.getBoolean("randomize-questions", true);
            loaded.put(id, new ExamDefinition(
                    id, qualification, passingScore, randomize, title, description, questions));
        }
        exams = Map.copyOf(loaded);
    }

    public Optional<ExamDefinition> find(String id) {
        return Optional.ofNullable(exams.get(id.toLowerCase(Locale.ROOT)));
    }

    public Collection<ExamDefinition> all() {
        return exams.values().stream().sorted(Comparator.comparing(ExamDefinition::id)).toList();
    }

    private static List<ExamQuestion> questions(
            ConfigurationSection exam,
            String defaultLocale,
            String examId
    ) {
        ConfigurationSection questions = requiredSection(exam, "questions");
        List<ExamQuestion> loaded = new ArrayList<>();
        for (String questionId : questions.getKeys(false)) {
            ConfigurationSection question = requiredSection(questions, questionId);
            LocalizedText prompt = localized(question, "prompt", defaultLocale);
            ConfigurationSection options = requiredSection(question, "options");
            Map<Character, LocalizedText> answers = new LinkedHashMap<>();
            Character correct = null;
            for (String optionId : options.getKeys(false)) {
                if (optionId.length() != 1 || !Character.isLetter(optionId.charAt(0))) {
                    throw new IllegalArgumentException("Invalid option in exam " + examId + ": " + optionId);
                }
                char key = Character.toUpperCase(optionId.charAt(0));
                ConfigurationSection option = requiredSection(options, optionId);
                if (answers.put(key, localized(option, "text", defaultLocale)) != null) {
                    throw new IllegalArgumentException(
                            "Question " + questionId + " in exam " + examId + " has duplicate option " + key);
                }
                if (option.getBoolean("correct", false)) {
                    if (correct != null) {
                        throw new IllegalArgumentException(
                                "Question " + questionId + " in exam " + examId + " has multiple correct options");
                    }
                    correct = key;
                }
            }
            if (correct == null) {
                throw new IllegalArgumentException(
                        "Question " + questionId + " in exam " + examId + " has no correct option");
            }
            loaded.add(new ExamQuestion(prompt, answers, correct));
        }
        return List.copyOf(loaded);
    }

    private static LocalizedText localized(
            ConfigurationSection parent,
            String path,
            String defaultLocale
    ) {
        ConfigurationSection values = parent.getConfigurationSection(path);
        if (values == null) {
            String scalar = parent.getString(path);
            if (scalar == null) {
                throw new IllegalArgumentException("Missing localized text at " + parent.getCurrentPath() + "." + path);
            }
            return new LocalizedText(Map.of(defaultLocale, scalar));
        }
        Map<String, String> localized = new LinkedHashMap<>();
        for (String locale : values.getKeys(false)) {
            String text = values.getString(locale);
            if (text != null) {
                localized.put(locale, text);
            }
        }
        return new LocalizedText(localized);
    }

    private static ConfigurationSection requiredSection(ConfigurationSection parent, String path) {
        ConfigurationSection section = parent.getConfigurationSection(path);
        if (section == null) {
            throw new IllegalArgumentException("Missing configuration section: " + parent.getCurrentPath() + "." + path);
        }
        return section;
    }

    private static String normalizeId(String raw, String type) {
        String id = raw.toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid " + type + " id: " + raw);
        }
        return id;
    }
}
