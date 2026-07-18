package dev.opencivitas.vehicle;

public enum VehicleResult {
    SUCCESS,
    CITIZEN_NOT_FOUND,
    VEHICLE_NOT_FOUND,
    NOT_OWNER,
    SELF_TRANSFER,
    OWNER_LIMIT_REACHED,
    FUEL_FULL,
    HEALTH_FULL,
    INVALID_STATE
}
