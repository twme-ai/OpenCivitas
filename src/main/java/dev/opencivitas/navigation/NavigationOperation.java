package dev.opencivitas.navigation;

import java.util.Optional;

public record NavigationOperation<T>(NavigationResult result, Optional<T> value) {
    public static <T> NavigationOperation<T> success(T value) {
        return new NavigationOperation<>(NavigationResult.SUCCESS, Optional.ofNullable(value));
    }

    public static <T> NavigationOperation<T> result(NavigationResult result) {
        return new NavigationOperation<>(result, Optional.empty());
    }
}
