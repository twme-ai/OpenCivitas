package dev.opencivitas.police;

import java.util.Optional;

public record DeathResult(CombatIncident incident, Optional<ForensicClue> clue) {
}
