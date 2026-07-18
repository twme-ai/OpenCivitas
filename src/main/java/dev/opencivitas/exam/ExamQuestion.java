package dev.opencivitas.exam;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

public record ExamQuestion(LocalizedText prompt, Map<Character, LocalizedText> options, char correctOption) {
    public ExamQuestion {
        if (options.size() < 2) {
            throw new IllegalArgumentException("Exam questions require at least two options");
        }
        Map<Character, LocalizedText> normalized = new LinkedHashMap<>();
        options.forEach((key, value) -> normalized.put(Character.toUpperCase(key), value));
        correctOption = Character.toUpperCase(correctOption);
        if (!normalized.containsKey(correctOption)) {
            throw new IllegalArgumentException("Correct option does not exist");
        }
        options = Collections.unmodifiableMap(normalized);
    }
}
