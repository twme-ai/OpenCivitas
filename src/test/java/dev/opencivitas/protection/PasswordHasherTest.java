package dev.opencivitas.protection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {
    @Test
    void passwordsUseSaltedVerifiableHashes() {
        PasswordHasher hasher = new PasswordHasher(
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        String first = hasher.hash("correct horse battery staple");
        String second = hasher.hash("correct horse battery staple");

        assertNotEquals(first, second);
        assertTrue(hasher.matches("correct horse battery staple", first));
        assertFalse(hasher.matches("incorrect", first));
        assertFalse(hasher.matches("correct horse battery staple", "not-a-valid-hash"));
        assertFalse(hasher.matches("correct horse battery staple",
                first.substring(0, first.lastIndexOf('$') + 1)
                        + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
        assertEquals(hasher.fingerprint("correct horse battery staple"),
                PasswordHasher.storedFingerprint(first));
    }
}
