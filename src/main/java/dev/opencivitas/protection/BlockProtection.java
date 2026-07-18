package dev.opencivitas.protection;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record BlockProtection(
        ProtectionKey key,
        UUID ownerId,
        String ownerName,
        ProtectionType type,
        boolean autoClose,
        Instant createdAt,
        Map<ProtectionSource, ProtectionAccess> access
) {
    public BlockProtection {
        access = Map.copyOf(access);
    }

    public BlockProtection withAccess(Map<ProtectionSource, ProtectionAccess> updated) {
        return new BlockProtection(key, ownerId, ownerName, type, autoClose, createdAt, updated);
    }

    public BlockProtection withAutoClose(boolean enabled) {
        return new BlockProtection(key, ownerId, ownerName, type, enabled, createdAt, access);
    }
}
