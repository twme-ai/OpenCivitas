package dev.opencivitas.police;

import java.util.Optional;

public record LawOperation<T>(LawResult result, Optional<T> value) {
    public static <T> LawOperation<T> success(T value) {
        return new LawOperation<>(LawResult.SUCCESS, Optional.of(value));
    }

    public static <T> LawOperation<T> failure(LawResult result) {
        return new LawOperation<>(result, Optional.empty());
    }
}
