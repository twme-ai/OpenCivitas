package dev.opencivitas.family;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FamilyRegistryTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID LAWYER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void immutableSnapshotRequiresBothSpousesToEnablePvp() {
        FamilyRegistry registry = new FamilyRegistry();
        registry.replaceAll(List.of(marriage(false, true)));

        assertTrue(registry.blocksPvp(ALICE, BOB));
        assertTrue(registry.blocksPvp(BOB, ALICE));

        Marriage mutual = marriage(true, true);
        registry.upsert(mutual);
        assertFalse(registry.blocksPvp(ALICE, BOB));
        registry.remove(mutual);
        assertFalse(registry.blocksPvp(ALICE, BOB));
    }

    private static Marriage marriage(boolean alicePvp, boolean bobPvp) {
        return new Marriage(1, ALICE, "Alice", BOB, "Bob", LAWYER,
                Instant.ofEpochMilli(1), null, null, null, alicePvp, bobPvp);
    }
}
