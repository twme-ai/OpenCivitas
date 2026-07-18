package dev.opencivitas.locale;

import java.util.Collection;
import java.util.Locale;

public final class LocaleResolver {
    private LocaleResolver() {
    }

    public static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        Locale locale = Locale.forLanguageTag(input.replace('_', '-'));
        if (locale.getLanguage().isBlank()) {
            return "";
        }
        if (locale.getCountry().isBlank()) {
            return locale.getLanguage().toLowerCase(Locale.ROOT);
        }
        return locale.getLanguage().toLowerCase(Locale.ROOT)
                + "_" + locale.getCountry().toUpperCase(Locale.ROOT);
    }

    public static String resolve(String requested, Collection<String> supported, String fallback) {
        String normalized = normalize(requested);
        for (String candidate : supported) {
            if (candidate.equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }

        int separator = normalized.indexOf('_');
        String language = separator < 0 ? normalized : normalized.substring(0, separator);
        if (!language.isBlank()) {
            for (String candidate : supported) {
                if (normalize(candidate).startsWith(language + "_")) {
                    return candidate;
                }
            }
        }
        return fallback;
    }

    public static boolean isExactSupported(String requested, Collection<String> supported) {
        String normalized = normalize(requested);
        return supported.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(normalized));
    }
}
