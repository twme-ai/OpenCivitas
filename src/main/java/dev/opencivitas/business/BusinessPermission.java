package dev.opencivitas.business;

import java.util.Locale;
import java.util.Optional;

public enum BusinessPermission {
    ADMINISTRATOR,
    FINANCIAL,
    CHEST_SHOP,
    DEFAULT;

    public static Optional<BusinessPermission> parse(String input) {
        String normalized = input.toUpperCase(Locale.ROOT).replace('-', '_');
        if (normalized.equals("CHESTSHOP")) {
            normalized = "CHEST_SHOP";
        }
        try {
            return Optional.of(valueOf(normalized));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
