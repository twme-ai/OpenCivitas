package dev.opencivitas.election;

import java.time.Duration;

public record ElectionDefinition(
        String id,
        ElectionMethod method,
        int seats,
        int termDays,
        Duration minimumCitizenship,
        Duration minimumTotalPlaytime,
        Duration minimumRecentPlaytime,
        Duration recentWindow,
        boolean runningMateRequired,
        String runningMateOffice,
        boolean disallowImmediateReelection,
        Duration runningMateMinimumCitizenship,
        Duration runningMateMinimumTotalPlaytime,
        Duration runningMateMinimumRecentPlaytime,
        boolean runningMateDisallowMostRecentPresident
) {
}
