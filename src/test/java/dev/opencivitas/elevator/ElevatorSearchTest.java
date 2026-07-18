package dev.opencivitas.elevator;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElevatorSearchTest {
    @Test
    void findsNearestFloorInEachDirection() {
        Set<Integer> floors = Set.of(10, 30, 50);
        assertEquals(50, ElevatorSearch.nextFloor(30, -64, 320, 1, 384, floors::contains).orElseThrow());
        assertEquals(10, ElevatorSearch.nextFloor(30, -64, 320, -1, 384, floors::contains).orElseThrow());
    }

    @Test
    void skipsNonElevatorHeightsBetweenFloors() {
        assertEquals(72, ElevatorSearch.nextFloor(
                64, -64, 320, 1, 384, y -> y == 72).orElseThrow());
    }

    @Test
    void respectsConfiguredDistanceAndWorldBounds() {
        assertTrue(ElevatorSearch.nextFloor(64, -64, 320, 1, 7, y -> y == 72).isEmpty());
        assertTrue(ElevatorSearch.nextFloor(318, -64, 320, 1, 384, y -> y == 320).isEmpty());
        assertTrue(ElevatorSearch.nextFloor(-63, -64, 320, -1, 384, y -> y == -65).isEmpty());
    }

    @Test
    void rejectsInvalidDirections() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ElevatorSearch.nextFloor(64, -64, 320, 0, 384, ignored -> true));
    }
}
