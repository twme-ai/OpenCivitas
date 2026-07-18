package dev.opencivitas.police;

import java.util.List;
import java.util.Optional;

public record PoliceReportDetails(
        PoliceReport report,
        CombatIncident incident,
        Optional<ForensicClue> clue,
        Optional<PoliceCharge> charge,
        List<PoliceReportEvent> events
) {
    public PoliceReportDetails {
        clue = clue == null ? Optional.empty() : clue;
        charge = charge == null ? Optional.empty() : charge;
        events = List.copyOf(events);
    }
}
