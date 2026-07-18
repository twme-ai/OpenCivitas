package dev.opencivitas.protection;

import java.util.Set;
import java.util.UUID;

public record ProtectionGroup(UUID ownerId, String name, Set<UUID> members) {
    public ProtectionGroup {
        members = Set.copyOf(members);
    }
}
