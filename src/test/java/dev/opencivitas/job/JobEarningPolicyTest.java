package dev.opencivitas.job;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobEarningPolicyTest {
    @Test
    void exactRuleOverridesSameJobWildcardWithoutSuppressingOtherJobs() {
        JobEarningPolicy policy = new JobEarningPolicy(1_000, List.of(
                rule("hunter", JobActionType.KILL, "*", 100),
                rule("hunter", JobActionType.KILL, "ZOMBIE", 200),
                rule("ranger", JobActionType.KILL, "ZOMBIE", 300),
                rule("miner", JobActionType.BREAK, "DIAMOND_ORE", 400)));

        Map<String, Long> zombie = policy.roll(JobActionType.KILL, "zombie", new Random(1)).stream()
                .collect(Collectors.toMap(JobEarningCandidate::jobId, JobEarningCandidate::amountCents));
        assertEquals(Map.of("hunter", 200L, "ranger", 300L), zombie);
        assertEquals(100, policy.roll(JobActionType.KILL, "COW", new Random(1))
                .getFirst().amountCents());
        assertTrue(policy.hasRules(JobActionType.BREAK, "diamond_ore"));
        assertFalse(policy.hasRules(JobActionType.BREAK, "STONE"));
    }

    @Test
    void payoutBoundaryAlwaysUsesTheNextCompleteBucket() {
        JobEarningPolicy policy = new JobEarningPolicy(
                1_000, List.of(rule("miner", JobActionType.BREAK, "STONE", 100)));

        assertEquals(1_000, policy.nextPayoutAt(0));
        assertEquals(1_000, policy.nextPayoutAt(999));
        assertEquals(2_000, policy.nextPayoutAt(1_000));
        assertEquals(1, policy.ruleCount());
    }

    @Test
    void rejectsInvalidRuleEconomics() {
        assertThrows(IllegalArgumentException.class, () -> new JobEarningRule(
                "miner", JobActionType.BREAK, "STONE", 0, 100, 1));
        assertThrows(IllegalArgumentException.class, () -> new JobEarningRule(
                "miner", JobActionType.BREAK, "STONE", 200, 100, 1));
        assertThrows(IllegalArgumentException.class, () -> new JobEarningRule(
                "miner", JobActionType.BREAK, "STONE", 100, 100, 1.1));
    }

    private static JobEarningRule rule(
            String job, JobActionType action, String target, long amount) {
        return new JobEarningRule(job, action, target, amount, amount, 1);
    }
}
