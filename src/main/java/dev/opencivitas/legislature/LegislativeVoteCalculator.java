package dev.opencivitas.legislature;

public final class LegislativeVoteCalculator {
    private LegislativeVoteCalculator() {
    }

    public static VoteDecision decide(
            LegislativeChamber chamber,
            VoteThreshold threshold,
            int yes,
            int no,
            int abstain
    ) {
        if (yes < 0 || no < 0 || abstain < 0) {
            throw new IllegalArgumentException("Vote counts cannot be negative");
        }
        int statutorySeats = chamber == LegislativeChamber.HOUSE ? 11 : 6;
        int affirmativeFloor = chamber == LegislativeChamber.HOUSE ? 4 : 2;
        int modifiedSeats = Math.max(0, statutorySeats - abstain);
        int quorumRequired = (modifiedSeats + 1) / 2;
        int participation = yes + no + abstain;
        boolean quorum = participation >= quorumRequired;
        int decisive = yes + no;
        boolean thresholdMet = switch (threshold) {
            case SIMPLE -> yes > no;
            case TWO_THIRDS -> decisive > 0 && yes * 3L >= decisive * 2L;
            case FOUR_FIFTHS -> decisive > 0 && yes * 5L >= decisive * 4L;
        };
        boolean passed = quorum && yes >= affirmativeFloor && thresholdMet;
        return new VoteDecision(yes, no, abstain, quorumRequired, quorum, passed);
    }
}
