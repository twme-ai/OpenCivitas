package dev.opencivitas.chat;

import java.util.Optional;

public record ChatOperation<T>(ChatResult result, Optional<T> value) {
    public static <T> ChatOperation<T> success(T value) {
        return new ChatOperation<>(ChatResult.SUCCESS, Optional.ofNullable(value));
    }

    public static <T> ChatOperation<T> result(ChatResult result) {
        return new ChatOperation<>(result, Optional.empty());
    }
}
