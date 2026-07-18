package dev.opencivitas.network;

import java.util.UUID;

public record NetworkPlayer(UUID uuid, String name, String nodeId, long updatedAt) {
}
