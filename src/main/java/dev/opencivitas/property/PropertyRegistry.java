package dev.opencivitas.property;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class PropertyRegistry {
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    public void replaceAll(Collection<Property> properties) {
        snapshot.set(Snapshot.of(properties));
    }

    public void upsert(Property property) {
        List<Property> updated = new ArrayList<>(snapshot.get().properties());
        updated.removeIf(existing -> existing.id() == property.id());
        updated.add(property);
        snapshot.set(Snapshot.of(updated));
    }

    public void remove(long propertyId) {
        snapshot.updateAndGet(current -> Snapshot.of(current.properties().stream()
                .filter(property -> property.id() != propertyId).toList()));
    }

    public Optional<Property> find(String plotId) {
        return Optional.ofNullable(snapshot.get().byId().get(plotId.toLowerCase(Locale.ROOT)));
    }

    public Optional<Property> at(String world, int x, int y, int z) {
        return snapshot.get().byChunk()
                .getOrDefault(new WorldChunk(world, x >> 4, z >> 4), List.of())
                .stream()
                .filter(property -> property.contains(world, x, y, z))
                .findFirst();
    }

    public List<Property> relatedTo(UUID player) {
        return snapshot.get().properties().stream()
                .filter(property -> player.equals(property.titleholderId())
                        || player.equals(property.tenantId())
                        || property.trusted().contains(player))
                .toList();
    }

    public List<Property> all() {
        return snapshot.get().properties();
    }

    private record WorldChunk(String world, int x, int z) {
    }

    private record Snapshot(
            List<Property> properties,
            Map<String, Property> byId,
            Map<WorldChunk, List<Property>> byChunk
    ) {
        private static Snapshot empty() {
            return new Snapshot(List.of(), Map.of(), Map.of());
        }

        private static Snapshot of(Collection<Property> values) {
            List<Property> sorted = values.stream()
                    .sorted(Comparator.comparing(Property::plotId, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            Map<String, Property> ids = new HashMap<>();
            Map<WorldChunk, List<Property>> mutableChunks = new HashMap<>();
            for (Property property : sorted) {
                ids.put(property.plotId().toLowerCase(Locale.ROOT), property);
                for (int chunkX = property.minX() >> 4; chunkX <= property.maxX() >> 4; chunkX++) {
                    for (int chunkZ = property.minZ() >> 4; chunkZ <= property.maxZ() >> 4; chunkZ++) {
                        mutableChunks.computeIfAbsent(
                                new WorldChunk(property.worldName(), chunkX, chunkZ),
                                ignored -> new ArrayList<>()).add(property);
                    }
                }
            }
            Map<WorldChunk, List<Property>> chunks = new HashMap<>();
            mutableChunks.forEach((chunk, properties) -> chunks.put(chunk, List.copyOf(properties)));
            return new Snapshot(sorted, Map.copyOf(ids), Map.copyOf(chunks));
        }
    }
}
