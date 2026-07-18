package dev.opencivitas.network;

public record NetworkNode(String id, String displayName, int onlinePlayers, long heartbeatAt) {
}
