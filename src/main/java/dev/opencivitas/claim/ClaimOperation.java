package dev.opencivitas.claim;

import java.util.Optional;

public record ClaimOperation(
        ClaimResult result,
        Optional<LandClaim> claim,
        int remainingBlocks,
        long balanceCents
) {
    public static ClaimOperation failed(ClaimResult result) {
        return new ClaimOperation(result, Optional.empty(), 0, 0);
    }

    public static ClaimOperation succeeded(LandClaim claim, int remainingBlocks, long balanceCents) {
        return new ClaimOperation(ClaimResult.SUCCESS, Optional.ofNullable(claim), remainingBlocks, balanceCents);
    }
}
