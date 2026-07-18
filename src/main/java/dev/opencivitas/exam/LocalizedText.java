package dev.opencivitas.exam;

import dev.opencivitas.locale.LocaleResolver;

import java.util.LinkedHashMap;
import java.util.Map;

public record LocalizedText(Map<String, String> values) {
    public LocalizedText {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Localized text must contain at least one value");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        values.forEach((locale, text) -> normalized.put(LocaleResolver.normalize(locale), text));
        values = Map.copyOf(normalized);
    }

    public String resolve(String requestedLocale, String defaultLocale) {
        String requested = LocaleResolver.normalize(requestedLocale);
        String exact = values.get(requested);
        if (exact != null) {
            return exact;
        }
        String language = requested.contains("_") ? requested.substring(0, requested.indexOf('_')) : requested;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().equals(language) || entry.getKey().startsWith(language + "_")) {
                return entry.getValue();
            }
        }
        String fallback = values.get(LocaleResolver.normalize(defaultLocale));
        return fallback != null ? fallback : values.values().iterator().next();
    }
}
