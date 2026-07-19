package dev.opencivitas.job;

import java.util.OptionalLong;
import java.util.random.RandomGenerator;

public record JobEarningRule(
        String jobId,
        JobActionType actionType,
        String targetKey,
        long minimumCents,
        long maximumCents,
        double chance
) {
    public JobEarningRule {
        if (jobId == null || jobId.isBlank() || targetKey == null || targetKey.isBlank()) {
            throw new IllegalArgumentException("Job earning rule keys are required");
        }
        if (actionType == null || minimumCents <= 0 || maximumCents < minimumCents) {
            throw new IllegalArgumentException("Job earning rule amounts are invalid");
        }
        if (!Double.isFinite(chance) || chance <= 0 || chance > 1) {
            throw new IllegalArgumentException("Job earning chance must be greater than zero and at most one");
        }
    }

    public OptionalLong roll(RandomGenerator random) {
        if (chance < 1 && random.nextDouble() >= chance) return OptionalLong.empty();
        if (minimumCents == maximumCents) return OptionalLong.of(minimumCents);
        return OptionalLong.of(random.nextLong(minimumCents, maximumCents + 1));
    }
}
