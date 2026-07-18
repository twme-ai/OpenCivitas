package dev.opencivitas.legislature;

import java.util.Optional;

public record LegislativeOperation(
        LegislativeActionResult result,
        Optional<LegislativeBill> bill,
        Optional<LegislativeVoteResult> voteResult
) {
    public static LegislativeOperation result(LegislativeActionResult result) {
        return new LegislativeOperation(result, Optional.empty(), Optional.empty());
    }

    public static LegislativeOperation bill(LegislativeBill bill) {
        return new LegislativeOperation(
                LegislativeActionResult.SUCCESS, Optional.of(bill), Optional.empty());
    }
}
