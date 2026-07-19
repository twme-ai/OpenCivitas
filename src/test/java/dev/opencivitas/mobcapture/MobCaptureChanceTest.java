package dev.opencivitas.mobcapture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobCaptureChanceTest {
    @Test
    void oneInThreeAcceptsOnlyTheFirstRoll() {
        MobCaptureChance chance = new MobCaptureChance(1, 3);

        assertTrue(chance.succeeds(0));
        assertFalse(chance.succeeds(1));
        assertFalse(chance.succeeds(2));
        assertThrows(IllegalArgumentException.class, () -> chance.succeeds(3));
    }

    @Test
    void invalidFractionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MobCaptureChance(0, 3));
        assertThrows(IllegalArgumentException.class, () -> new MobCaptureChance(4, 3));
        assertThrows(IllegalArgumentException.class, () -> new MobCaptureChance(1, 0));
    }
}
