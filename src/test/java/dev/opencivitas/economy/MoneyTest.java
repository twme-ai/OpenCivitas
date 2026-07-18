package dev.opencivitas.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {
    @Test
    void parsesExactCentsWithoutFloatingPoint() {
        assertEquals(120_000, Money.parseCents("1200.00"));
        assertEquals(1, Money.parsePositiveCents("0.01"));
        assertEquals(100, Money.parsePositiveCents("1"));
    }

    @Test
    void rejectsAmbiguousOrNonPositiveInput() {
        assertThrows(IllegalArgumentException.class, () -> Money.parsePositiveCents("0"));
        assertThrows(IllegalArgumentException.class, () -> Money.parsePositiveCents("-1"));
        assertThrows(IllegalArgumentException.class, () -> Money.parsePositiveCents("1.001"));
        assertThrows(IllegalArgumentException.class, () -> Money.parsePositiveCents("1e3"));
        assertThrows(IllegalArgumentException.class, () -> Money.parsePositiveCents("1,000"));
    }

    @Test
    void formatsSignedCurrencyConsistently() {
        assertEquals("$1,234.56", Money.format(123_456, "$"));
        assertEquals("-$5.00", Money.format(-500, "$"));
        assertThrows(IllegalArgumentException.class, () -> Money.format(Long.MIN_VALUE, "$"));
    }
}
