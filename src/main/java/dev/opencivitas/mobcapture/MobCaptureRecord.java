package dev.opencivitas.mobcapture;

import java.time.Instant;
import java.util.UUID;

public record MobCaptureRecord(
        long id,
        UUID actorId,
        String actorName,
        UUID targetId,
        String entityType,
        String jobId,
        String world,
        double x,
        double y,
        double z,
        long feeCents,
        String status,
        Instant createdAt
) {
}
