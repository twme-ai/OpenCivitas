package dev.opencivitas.elevator;

import java.util.OptionalInt;
import java.util.function.IntPredicate;

public final class ElevatorSearch {
    private ElevatorSearch() {
    }

    public static OptionalInt nextFloor(
            int sourceY,
            int minimumY,
            int maximumYExclusive,
            int direction,
            int maximumDistance,
            IntPredicate isElevatorFloor
    ) {
        if (direction != -1 && direction != 1) {
            throw new IllegalArgumentException("direction must be -1 or 1");
        }
        if (maximumDistance < 1 || minimumY >= maximumYExclusive
                || sourceY < minimumY || sourceY >= maximumYExclusive) {
            return OptionalInt.empty();
        }

        long rawLimit = (long) sourceY + (long) direction * maximumDistance;
        int limit = direction > 0
                ? (int) Math.min(rawLimit, maximumYExclusive - 1L)
                : (int) Math.max(rawLimit, minimumY);
        for (int y = sourceY + direction;
             direction > 0 ? y <= limit : y >= limit;
             y += direction) {
            if (isElevatorFloor.test(y)) return OptionalInt.of(y);
        }
        return OptionalInt.empty();
    }
}
