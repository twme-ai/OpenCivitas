package dev.opencivitas.job;

import java.time.Instant;

public record CitizenLicense(String id, Instant grantedAt, Instant expiresAt) {
}
