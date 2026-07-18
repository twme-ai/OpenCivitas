package dev.opencivitas.protection;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProtectionRegistryTest {
    private static final UUID ALICE = id(1);
    private static final UUID BOB = id(2);

    @Test
    void resolvesDirectGroupPermissionPasswordAndTrustSourcesByStrength() {
        ProtectionSource direct = new ProtectionSource(ProtectionSourceType.PLAYER, BOB.toString());
        ProtectionSource group = new ProtectionSource(ProtectionSourceType.GROUP, "staff");
        ProtectionSource permission = new ProtectionSource(ProtectionSourceType.PERMISSION, "group.doctor");
        ProtectionSource password = new ProtectionSource(ProtectionSourceType.PASSWORD, "encoded-password");
        BlockProtection protection = new BlockProtection(
                new ProtectionKey("world", 1, 2, 3), ALICE, "Alice", ProtectionType.PRIVATE,
                false, Instant.EPOCH, Map.of(direct, ProtectionAccess.NORMAL, group, ProtectionAccess.ADMIN));
        ProtectionRegistry registry = new ProtectionRegistry();
        registry.replaceAll(new ProtectionState(
                List.of(protection),
                List.of(new ProtectionGroup(ALICE, "staff", Set.of(BOB))),
                Map.of(ALICE, Map.of(permission, ProtectionAccess.NORMAL, password, ProtectionAccess.ADMIN))));

        assertEquals(ProtectionAccess.ADMIN,
                registry.effectiveAccess(protection, BOB, ignored -> false, ignored -> false));
        UUID carol = id(3);
        assertEquals(ProtectionAccess.NORMAL,
                registry.effectiveAccess(protection, carol, "group.doctor"::equals, ignored -> false));
        assertEquals(ProtectionAccess.ADMIN,
                registry.effectiveAccess(protection, carol, ignored -> false, "encoded-password"::equals));
        assertNull(registry.effectiveAccess(protection, carol, ignored -> false, ignored -> false));
    }

    private static UUID id(long suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
