package dev.opencivitas.legislature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegislativeVoteCalculatorTest {
    @Test
    void houseRequiresQuorumAndFourAffirmativeVotes() {
        assertFalse(LegislativeVoteCalculator.decide(
                LegislativeChamber.HOUSE, VoteThreshold.SIMPLE, 4, 0, 0).passed());
        assertTrue(LegislativeVoteCalculator.decide(
                LegislativeChamber.HOUSE, VoteThreshold.SIMPLE, 4, 0, 2).passed());
        assertFalse(LegislativeVoteCalculator.decide(
                LegislativeChamber.HOUSE, VoteThreshold.SIMPLE, 3, 0, 3).passed());
    }

    @Test
    void senateRequiresTwoAffirmativeVotesAndDynamicMajority() {
        assertTrue(LegislativeVoteCalculator.decide(
                LegislativeChamber.SENATE, VoteThreshold.SIMPLE, 2, 1, 0).passed());
        assertFalse(LegislativeVoteCalculator.decide(
                LegislativeChamber.SENATE, VoteThreshold.SIMPLE, 1, 0, 2).passed());
    }

    @Test
    void supermajorityThresholdsUseExactFractions() {
        assertTrue(LegislativeVoteCalculator.decide(
                LegislativeChamber.HOUSE, VoteThreshold.TWO_THIRDS, 4, 2, 0).passed());
        assertFalse(LegislativeVoteCalculator.decide(
                LegislativeChamber.HOUSE, VoteThreshold.TWO_THIRDS, 4, 3, 0).passed());
        assertTrue(LegislativeVoteCalculator.decide(
                LegislativeChamber.HOUSE, VoteThreshold.FOUR_FIFTHS, 4, 1, 1).passed());
        assertFalse(LegislativeVoteCalculator.decide(
                LegislativeChamber.HOUSE, VoteThreshold.FOUR_FIFTHS, 4, 2, 0).passed());
    }
}
