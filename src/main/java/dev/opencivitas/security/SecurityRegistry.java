package dev.opencivitas.security;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class SecurityRegistry {
    private final AtomicReference<State> state = new AtomicReference<>(new State(Map.of(), Map.of(), Map.of()));

    public void replaceAll(List<SecurityCamera> cameras, List<SecurityComputer> computers) {
        Map<Long, SecurityCamera> byCameraId = new HashMap<>();
        for (SecurityCamera camera : cameras) byCameraId.put(camera.id(), camera);
        Map<Long, SecurityComputer> byComputerId = new HashMap<>();
        Map<String, SecurityComputer> byLocation = new HashMap<>();
        for (SecurityComputer computer : computers) {
            byComputerId.put(computer.id(), computer);
            byLocation.put(key(computer.world(), computer.x(), computer.y(), computer.z()), computer);
        }
        state.set(new State(Map.copyOf(byCameraId), Map.copyOf(byComputerId), Map.copyOf(byLocation)));
    }

    public void upsert(SecurityCamera camera) {
        State current = state.get();
        Map<Long, SecurityCamera> cameras = new HashMap<>(current.cameras());
        cameras.put(camera.id(), camera);
        state.set(new State(Map.copyOf(cameras), current.computers(), current.computerLocations()));
    }

    public void upsert(SecurityComputer computer) {
        State current = state.get();
        Map<Long, SecurityComputer> computers = new HashMap<>(current.computers());
        Map<String, SecurityComputer> locations = new HashMap<>(current.computerLocations());
        SecurityComputer replaced = computers.put(computer.id(), computer);
        if (replaced != null) locations.remove(key(replaced.world(), replaced.x(), replaced.y(), replaced.z()));
        locations.put(key(computer.world(), computer.x(), computer.y(), computer.z()), computer);
        state.set(new State(current.cameras(), Map.copyOf(computers), Map.copyOf(locations)));
    }

    public void removeCamera(long cameraId) {
        State current = state.get();
        Map<Long, SecurityCamera> cameras = new HashMap<>(current.cameras());
        cameras.remove(cameraId);
        state.set(new State(Map.copyOf(cameras), current.computers(), current.computerLocations()));
    }

    public void removeComputer(long computerId) {
        State current = state.get();
        SecurityComputer removed = current.computers().get(computerId);
        if (removed == null) return;
        Map<Long, SecurityComputer> computers = new HashMap<>(current.computers());
        Map<String, SecurityComputer> locations = new HashMap<>(current.computerLocations());
        computers.remove(computerId);
        locations.remove(key(removed.world(), removed.x(), removed.y(), removed.z()));
        state.set(new State(current.cameras(), Map.copyOf(computers), Map.copyOf(locations)));
    }

    public Optional<SecurityCamera> camera(long id) {
        return Optional.ofNullable(state.get().cameras().get(id));
    }

    public Optional<SecurityComputer> computer(long id) {
        return Optional.ofNullable(state.get().computers().get(id));
    }

    public Optional<SecurityComputer> computerAt(String world, int x, int y, int z) {
        return Optional.ofNullable(state.get().computerLocations().get(key(world, x, y, z)));
    }

    public Collection<SecurityCamera> cameras() {
        return state.get().cameras().values();
    }

    private static String key(String world, int x, int y, int z) {
        return world + ':' + x + ':' + y + ':' + z;
    }

    private record State(
            Map<Long, SecurityCamera> cameras,
            Map<Long, SecurityComputer> computers,
            Map<String, SecurityComputer> computerLocations) {
    }
}
