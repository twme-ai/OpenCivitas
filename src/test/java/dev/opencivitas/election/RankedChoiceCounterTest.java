package dev.opencivitas.election;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RankedChoiceCounterTest {
    @Test
    void instantRunoffRedistributesEliminatedBallots() {
        ElectionCount count = RankedChoiceCounter.count(
                ElectionMethod.IRV,
                List.of("alice", "bob", "carol"),
                List.of(ballot("alice", "bob"), ballot("bob"), ballot("carol", "bob")),
                1);

        assertEquals(List.of("bob"), count.winners());
        assertEquals(2, count.rounds().size());
        assertEquals("carol", count.rounds().getFirst().eliminated());
        assertEquals(new BigDecimal("2"), count.rounds().getLast().tallies().get("bob"));
    }

    @Test
    void stvTransfersOnlyTheWinnersFractionalSurplus() {
        ElectionCount count = RankedChoiceCounter.count(
                ElectionMethod.STV,
                List.of("alice", "bob", "carol"),
                List.of(
                        ballot("alice", "bob"), ballot("alice", "bob"),
                        ballot("alice", "bob"), ballot("alice", "bob"),
                        ballot("bob", "carol"), ballot("bob", "carol"),
                        ballot("carol", "bob"), ballot("carol", "bob")),
                2);

        assertEquals(List.of("alice", "bob"), count.winners());
        assertEquals(new BigDecimal("3.00"), count.rounds().getLast().tallies().get("bob"));
    }

    @Test
    void stvEliminatesAndTransfersWhenNobodyReachesQuota() {
        ElectionCount count = RankedChoiceCounter.count(
                ElectionMethod.STV,
                List.of("alice", "bob", "carol"),
                List.of(
                        ballot("alice", "bob"), ballot("alice", "bob"), ballot("alice", "bob"),
                        ballot("bob", "carol"), ballot("bob", "carol"), ballot("carol", "bob")),
                2);

        assertEquals(List.of("alice", "bob"), count.winners());
    }

    @Test
    void tiedReferendumDoesNotPass() {
        ElectionCount count = RankedChoiceCounter.count(
                ElectionMethod.REFERENDUM,
                List.of("yes", "no"),
                List.of(ballot("yes"), ballot("no")),
                1);

        assertEquals(List.of("no"), count.winners());
    }

    private static RankedBallot ballot(String... preferences) {
        return new RankedBallot(List.of(preferences));
    }
}
