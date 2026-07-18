package dev.opencivitas.protection;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProtectionState(
        List<BlockProtection> protections,
        List<ProtectionGroup> groups,
        Map<UUID, Map<ProtectionSource, ProtectionAccess>> trust
) {
    public ProtectionState {
        protections = List.copyOf(protections);
        groups = List.copyOf(groups);
        trust = trust.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Map.copyOf(entry.getValue())));
    }
}
