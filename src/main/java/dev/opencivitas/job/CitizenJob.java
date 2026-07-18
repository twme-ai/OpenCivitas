package dev.opencivitas.job;

import java.time.Instant;

public record CitizenJob(String id, JobCategory category, Instant joinedAt, String appointedBy) {
}
