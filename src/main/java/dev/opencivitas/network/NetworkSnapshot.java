package dev.opencivitas.network;

import java.util.List;

public record NetworkSnapshot(List<NetworkNode> nodes, List<NetworkPlayer> players) {
    public NetworkSnapshot {
        nodes = List.copyOf(nodes);
        players = List.copyOf(players);
    }
}
