package dev.opencivitas.business;

import java.time.Instant;
import java.util.UUID;

public record BusinessOffer(
        long businessId,
        String businessSlug,
        String businessName,
        UUID offeredBy,
        String offeredByName,
        BusinessRole role,
        long wageCents,
        Instant offeredAt,
        Instant expiresAt
) {
}
