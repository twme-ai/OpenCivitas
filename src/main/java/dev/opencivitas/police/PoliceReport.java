package dev.opencivitas.police;

import java.time.Instant;
import java.util.UUID;

public record PoliceReport(
        long id,
        long incidentId,
        UUID reporterId,
        String reporterName,
        UUID suspectId,
        String suspectName,
        ReportStatus status,
        UUID assignedOfficerId,
        String assignedOfficerName,
        Instant filedAt,
        Instant updatedAt,
        String resolution
) {
}
