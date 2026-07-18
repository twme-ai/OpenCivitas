package dev.opencivitas.claim;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimRegistryTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000060");
    private static final UUID TRUSTED = UUID.fromString("00000000-0000-0000-0000-000000000061");

    @Test
    void snapshotsSupportTickThreadSpatialAuthorization() {
        ClaimRegistry registry = new ClaimRegistry(List.of("Wilderness"));
        LandClaim claim = claim(Set.of(TRUSTED));

        registry.replaceAll(List.of(claim));

        assertTrue(registry.enabled("wilderness"));
        assertFalse(registry.enabled("world"));
        assertEquals(claim, registry.at("wilderness", 5, 8).orElseThrow());
        assertTrue(registry.at("wilderness", 5, 8).orElseThrow().canBuild(TRUSTED));
        assertTrue(registry.at("wilderness", 5, 8).orElseThrow().canBuild(OWNER));
        assertTrue(registry.at("wilderness", 11, 8).isEmpty());

        registry.remove(claim.id());
        assertTrue(registry.all().isEmpty());
    }

    private static LandClaim claim(Set<UUID> trusted) {
        return new LandClaim(
                1, OWNER, "Owner", "wilderness", 0, 10, 0, 10,
                false, trusted, Instant.EPOCH);
    }
}
