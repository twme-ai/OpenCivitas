package dev.opencivitas.court;

import java.util.List;
import java.util.Map;

public record CourtCaseDetails(
        CourtCase courtCase,
        Map<String, String> counsel,
        List<CourtJudge> judges,
        List<CourtEvidence> evidence,
        List<CourtDocketEntry> docket,
        List<CourtOrder> orders,
        List<CourtWarrant> warrants
) {
    public CourtCaseDetails {
        counsel = Map.copyOf(counsel);
        judges = List.copyOf(judges);
        evidence = List.copyOf(evidence);
        docket = List.copyOf(docket);
        orders = List.copyOf(orders);
        warrants = List.copyOf(warrants);
    }
}
