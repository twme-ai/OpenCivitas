package dev.opencivitas.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Money {
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+(?:\\.[0-9]{1,2})?");

    private Money() {
    }

    public static long parseCents(String input) {
        if (input == null || !DECIMAL.matcher(input).matches()) {
            throw new IllegalArgumentException("Invalid currency amount");
        }

        try {
            return new BigDecimal(input)
                    .setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2)
                    .longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Currency amount is out of range", exception);
        }
    }

    public static long parsePositiveCents(String input) {
        long cents = parseCents(input);
        if (cents <= 0) {
            throw new IllegalArgumentException("Currency amount must be positive");
        }
        return cents;
    }

    public static String format(long cents, String symbol) {
        if (cents == Long.MIN_VALUE) {
            throw new IllegalArgumentException("Currency amount is out of range");
        }
        long absolute = Math.abs(cents);
        String amount = String.format(Locale.US, "%,d.%02d", absolute / 100, absolute % 100);
        return (cents < 0 ? "-" : "") + symbol + amount;
    }
}
