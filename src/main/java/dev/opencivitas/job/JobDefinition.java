package dev.opencivitas.job;

public record JobDefinition(
        String id,
        JobCategory category,
        String qualification,
        boolean selfJoin
) {
}
