package dev.opencivitas.security;

import java.util.UUID;

public record ComputerAccess(UUID playerId, String playerName, long grantedAt) {
}
