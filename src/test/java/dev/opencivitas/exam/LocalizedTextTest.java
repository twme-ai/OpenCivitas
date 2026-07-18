package dev.opencivitas.exam;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalizedTextTest {
    private final LocalizedText text = new LocalizedText(Map.of(
            "en_US", "English",
            "zh_TW", "Traditional Chinese"
    ));

    @Test
    void resolvesExactAndLanguageMatches() {
        assertEquals("Traditional Chinese", text.resolve("zh_TW", "en_US"));
        assertEquals("English", text.resolve("en_GB", "en_US"));
    }

    @Test
    void fallsBackToConfiguredDefault() {
        assertEquals("English", text.resolve("fr_FR", "en_US"));
    }
}
