package dev.opencivitas.vehicle;

public record VehiclePickup(VehicleState state, byte[] storage) {
    public VehiclePickup {
        storage = storage == null ? new byte[0] : storage.clone();
    }

    @Override
    public byte[] storage() {
        return storage.clone();
    }
}
