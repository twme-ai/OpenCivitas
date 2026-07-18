package dev.opencivitas.security;

import java.util.List;
import java.util.Optional;

public record ComputerDashboard(
        SecurityComputer computer,
        Optional<CameraGroup> group,
        List<SecurityCamera> cameras,
        List<ComputerAccess> access
) {
    public ComputerDashboard {
        cameras = List.copyOf(cameras);
        access = List.copyOf(access);
    }
}
