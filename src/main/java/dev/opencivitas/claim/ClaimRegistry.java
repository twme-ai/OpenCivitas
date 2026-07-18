package dev.opencivitas.claim;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class ClaimRegistry {
    private final Set<String> enabledWorlds;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    public ClaimRegistry(Collection<String> enabledWorlds) {
        this.enabledWorlds = enabledWorlds.stream().map(String::toLowerCase).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public void replaceAll(Collection<LandClaim> loaded) {
        snapshot.set(Snapshot.of(loaded));
    }

    public void upsert(LandClaim claim) {
        List<LandClaim> updated = new java.util.ArrayList<>(snapshot.get().claims());
        updated.removeIf(existing -> existing.id() == claim.id());
        updated.add(claim);
        snapshot.set(Snapshot.of(updated));
    }

    public void remove(long claimId) {
        snapshot.updateAndGet(current -> Snapshot.of(current.claims().stream()
                .filter(claim -> claim.id() != claimId).toList()));
    }

    public Optional<LandClaim> at(String world, int x, int z) {
        return snapshot.get().byChunk()
                .getOrDefault(new WorldChunk(world, x >> 4, z >> 4), List.of())
                .stream()
                .filter(claim -> claim.contains(world, x, z))
                .findFirst();
    }

    public List<LandClaim> ownedBy(UUID player) {
        return snapshot.get().claims().stream().filter(claim -> claim.ownerId().equals(player)).toList();
    }

    public boolean enabled(String world) {
        return enabledWorlds.contains(world.toLowerCase());
    }

    public List<LandClaim> all() {
        return snapshot.get().claims();
    }

    private static List<LandClaim> sorted(Collection<LandClaim> values) {
        return values.stream().sorted(Comparator.comparingLong(LandClaim::id)).toList();
    }

    private record WorldChunk(String world, int x, int z) {
    }

    private record Snapshot(List<LandClaim> claims, java.util.Map<WorldChunk, List<LandClaim>> byChunk) {
        private static Snapshot empty() {
            return new Snapshot(List.of(), java.util.Map.of());
        }

        private static Snapshot of(Collection<LandClaim> values) {
            List<LandClaim> sorted = sorted(values);
            java.util.Map<WorldChunk, List<LandClaim>> mutable = new java.util.HashMap<>();
            for (LandClaim claim : sorted) {
                for (int chunkX = claim.minX() >> 4; chunkX <= claim.maxX() >> 4; chunkX++) {
                    for (int chunkZ = claim.minZ() >> 4; chunkZ <= claim.maxZ() >> 4; chunkZ++) {
                        mutable.computeIfAbsent(
                                new WorldChunk(claim.worldName(), chunkX, chunkZ), ignored -> new java.util.ArrayList<>())
                                .add(claim);
                    }
                }
            }
            java.util.Map<WorldChunk, List<LandClaim>> immutable = new java.util.HashMap<>();
            mutable.forEach((chunk, claims) -> immutable.put(chunk, List.copyOf(claims)));
            return new Snapshot(sorted, java.util.Map.copyOf(immutable));
        }
    }
}
