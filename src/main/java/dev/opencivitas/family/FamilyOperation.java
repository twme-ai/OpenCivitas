package dev.opencivitas.family;

import java.util.Optional;

public record FamilyOperation<T>(FamilyResult result, Optional<T> value) {
    public static <T> FamilyOperation<T> success(T value) {
        return new FamilyOperation<>(FamilyResult.SUCCESS, Optional.ofNullable(value));
    }

    public static <T> FamilyOperation<T> result(FamilyResult result) {
        return new FamilyOperation<>(result, Optional.empty());
    }
}
