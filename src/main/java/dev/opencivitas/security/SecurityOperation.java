package dev.opencivitas.security;

import java.util.Optional;

public record SecurityOperation<T>(SecurityResult result, Optional<T> value) {
    public static <T> SecurityOperation<T> result(SecurityResult result) {
        return new SecurityOperation<>(result, Optional.empty());
    }

    public static <T> SecurityOperation<T> success(T value) {
        return new SecurityOperation<>(SecurityResult.SUCCESS, Optional.of(value));
    }
}
