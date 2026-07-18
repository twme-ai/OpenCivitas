package dev.opencivitas.protection;

import java.util.Optional;

public record ProtectionOperation<T>(ProtectionResult result, Optional<T> value) {
    public static <T> ProtectionOperation<T> success(T value) {
        return new ProtectionOperation<>(ProtectionResult.SUCCESS, Optional.ofNullable(value));
    }

    public static <T> ProtectionOperation<T> failed(ProtectionResult result) {
        return new ProtectionOperation<>(result, Optional.empty());
    }
}
