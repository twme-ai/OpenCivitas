package dev.opencivitas.legislature;

public record VoteDecision(
        int yesVotes,
        int noVotes,
        int abstainVotes,
        int quorumRequired,
        boolean quorumMet,
        boolean passed
) {
}
