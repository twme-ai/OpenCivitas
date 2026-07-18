package dev.opencivitas.vehicle;

import java.util.Set;

public record VehicleAccess(boolean mechanic, Set<String> licenses) {
    public VehicleAccess {
        licenses = Set.copyOf(licenses);
    }

    public boolean hasLicense(String licenseId) {
        return licenseId == null || licenseId.isBlank() || licenses.contains(licenseId);
    }
}
