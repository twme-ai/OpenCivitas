package dev.opencivitas.vehicle;

public record VehicleOperation<T>(VehicleResult result, T value) {
    public static <T> VehicleOperation<T> success(T value) {
        return new VehicleOperation<>(VehicleResult.SUCCESS, value);
    }

    public static <T> VehicleOperation<T> result(VehicleResult result) {
        return new VehicleOperation<>(result, null);
    }
}
