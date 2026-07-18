package dev.opencivitas.court;

import java.time.Instant;
import java.util.UUID;

public record CourtEvidence(
        long id,
        UUID submittedBy,
        String submitterName,
        String description,
        byte[] itemData,
        Instant submittedAt
) {
    public CourtEvidence {
        itemData = itemData == null ? null : itemData.clone();
    }

    @Override
    public byte[] itemData() {
        return itemData == null ? null : itemData.clone();
    }
}
