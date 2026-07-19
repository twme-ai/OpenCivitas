package dev.opencivitas.job;

public record JobEarningCandidate(
        String jobId,
        JobActionType actionType,
        String targetKey,
        long amountCents
) {
    public JobEarningCandidate {
        if (jobId == null || jobId.isBlank() || targetKey == null || targetKey.isBlank()) {
            throw new IllegalArgumentException("Job earning candidate keys are required");
        }
        if (actionType == null || amountCents <= 0) {
            throw new IllegalArgumentException("Job earning candidates require an action and positive amount");
        }
    }
}
