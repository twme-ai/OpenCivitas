package dev.opencivitas.election;

import java.time.Instant;

public record Election(
        long id,
        String slug,
        String title,
        ElectionKind kind,
        String officeId,
        ElectionMethod method,
        int seats,
        int termDays,
        boolean runningMateRequired,
        String runningMateOffice,
        ElectionStatus status,
        Instant createdAt,
        Instant nominationsCloseAt,
        Instant votingOpensAt,
        Instant votingClosesAt,
        Instant closedAt
) {
    public ElectionPhase phase(long now) {
        if (status == ElectionStatus.CANCELLED) return ElectionPhase.CANCELLED;
        if (status == ElectionStatus.CLOSED) return ElectionPhase.CLOSED;
        if (now < nominationsCloseAt.toEpochMilli()) return ElectionPhase.NOMINATIONS;
        if (now < votingOpensAt.toEpochMilli()) return ElectionPhase.WAITING;
        if (now < votingClosesAt.toEpochMilli()) return ElectionPhase.VOTING;
        return ElectionPhase.ENDED;
    }
}
