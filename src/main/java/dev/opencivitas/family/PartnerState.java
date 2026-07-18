package dev.opencivitas.family;

import dev.opencivitas.navigation.SavedLocation;

import java.util.UUID;

public record PartnerState(
        long marriageId,
        UUID partnerId,
        String partnerName,
        boolean pvpAllowed,
        SavedLocation home
) {
}
