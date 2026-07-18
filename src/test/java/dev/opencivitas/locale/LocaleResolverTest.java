package dev.opencivitas.locale;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocaleResolverTest {
    private static final List<String> SUPPORTED = List.of("en_US", "zh_TW");

    @Test
    void normalizesMinecraftAndLanguageTagFormats() {
        assertEquals("en_US", LocaleResolver.normalize("en_us"));
        assertEquals("zh_TW", LocaleResolver.normalize("zh-TW"));
    }

    @Test
    void resolvesExactThenLanguageThenDefault() {
        assertEquals("zh_TW", LocaleResolver.resolve("zh_tw", SUPPORTED, "en_US"));
        assertEquals("en_US", LocaleResolver.resolve("en_GB", SUPPORTED, "en_US"));
        assertEquals("en_US", LocaleResolver.resolve("fr_FR", SUPPORTED, "en_US"));
    }

    @Test
    void explicitSelectionRequiresAnExactSupportedLocale() {
        assertTrue(LocaleResolver.isExactSupported("ZH-tw", SUPPORTED));
        assertFalse(LocaleResolver.isExactSupported("en_GB", SUPPORTED));
    }
}
