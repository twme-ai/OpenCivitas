package dev.opencivitas.citizen;

import java.time.Duration;

public record CitizenActivity(Duration total, Duration recent) {
}
