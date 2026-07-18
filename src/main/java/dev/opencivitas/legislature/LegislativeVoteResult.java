package dev.opencivitas.legislature;

import java.time.Instant;

public record LegislativeVoteResult(
        BillStatus stage,
        int yesVotes,
        int noVotes,
        int abstainVotes,
        int quorumRequired,
        VoteThreshold threshold,
        boolean passed,
        Instant talliedAt
) {
}
