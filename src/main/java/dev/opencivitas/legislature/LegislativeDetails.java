package dev.opencivitas.legislature;

import java.util.List;

public record LegislativeDetails(
        LegislativeBill bill,
        List<LegislativeAmendment> amendments,
        List<LegislativeVoteResult> voteResults,
        List<LegislativeEvent> events
) {
    public LegislativeDetails {
        amendments = List.copyOf(amendments);
        voteResults = List.copyOf(voteResults);
        events = List.copyOf(events);
    }
}
