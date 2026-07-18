package dev.opencivitas.protection;

import java.util.Locale;
import java.util.Optional;

public enum ProtectionType {
    PRIVATE(false, false, false),
    DISPLAY(true, false, false),
    DEPOSIT(true, true, false),
    WITHDRAWAL(true, false, true),
    PUBLIC(true, true, true);

    private final boolean publicOpen;
    private final boolean publicDeposit;
    private final boolean publicWithdraw;

    ProtectionType(boolean publicOpen, boolean publicDeposit, boolean publicWithdraw) {
        this.publicOpen = publicOpen;
        this.publicDeposit = publicDeposit;
        this.publicWithdraw = publicWithdraw;
    }

    public boolean publicOpen() {
        return publicOpen;
    }

    public boolean publicDeposit() {
        return publicDeposit;
    }

    public boolean publicWithdraw() {
        return publicWithdraw;
    }

    public static Optional<ProtectionType> parse(String value) {
        if (value == null) return Optional.empty();
        try {
            return Optional.of(valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
