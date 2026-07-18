package dev.opencivitas.health;

import java.util.Optional;

public record HealthOperation<T>(HealthResult result, Optional<T> value) {
    public static <T> HealthOperation<T> success(T value) {
        return new HealthOperation<>(HealthResult.SUCCESS, Optional.ofNullable(value));
    }

    public static <T> HealthOperation<T> result(HealthResult result) {
        return new HealthOperation<>(result, Optional.empty());
    }
}
