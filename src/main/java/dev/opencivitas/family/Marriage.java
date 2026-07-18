package dev.opencivitas.family;

import dev.opencivitas.navigation.SavedLocation;

import java.time.Instant;
import java.util.UUID;

public record Marriage(
        long id,
        UUID spouseAId,
        String spouseAName,
        UUID spouseBId,
        String spouseBName,
        UUID officiantId,
        Instant marriedAt,
        Instant dissolvedAt,
        String dissolutionReason,
        SavedLocation home,
        boolean spouseAPvpEnabled,
        boolean spouseBPvpEnabled
) {
    public boolean active() {
        return dissolvedAt == null;
    }

    public UUID partnerId(UUID playerId) {
        if (playerId.equals(spouseAId)) return spouseBId;
        if (playerId.equals(spouseBId)) return spouseAId;
        throw new IllegalArgumentException("Player is not part of this marriage");
    }

    public String partnerName(UUID playerId) {
        if (playerId.equals(spouseAId)) return spouseBName;
        if (playerId.equals(spouseBId)) return spouseAName;
        throw new IllegalArgumentException("Player is not part of this marriage");
    }

    public boolean partnerPvpAllowed() {
        return spouseAPvpEnabled && spouseBPvpEnabled;
    }
}
