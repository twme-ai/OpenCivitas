package dev.opencivitas.protection;

import java.util.Locale;
import java.util.Optional;

public enum ProtectionSourceType {
    PLAYER,
    GROUP,
    PERMISSION,
    PASSWORD;

    public static Optional<ProtectionSourceType> parse(String value) {
        if (value == null) return Optional.empty();
        try {
            return Optional.of(valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
