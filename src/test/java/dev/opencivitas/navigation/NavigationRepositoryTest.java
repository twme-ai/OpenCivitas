package dev.opencivitas.navigation;

import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavigationRepositoryTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final long NOW = 2_000_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private NavigationRepository navigation;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = NavigationRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        new CitizenRepository(database).register(ALICE, "Alice", "en_US", 0);
        navigation = new NavigationRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void namedHomesPersistUpdateAndRespectLimit() throws Exception {
        SavedLocation first = location("house", 1, 65, 2);
        SavedLocation second = location("office", 3, 70, 4);
        assertEquals(NavigationResult.SUCCESS, navigation.setHome(ALICE, first, 2, NOW).result());
        assertEquals(NavigationResult.SUCCESS, navigation.setHome(ALICE, second, 2, NOW + 1).result());
        assertEquals(NavigationResult.HOME_LIMIT_REACHED,
                navigation.setHome(ALICE, location("third", 5, 75, 6), 2, NOW + 2).result());

        SavedLocation moved = location("HOUSE", 8, 80, 9);
        assertEquals(NavigationResult.SUCCESS, navigation.setHome(ALICE, moved, 2, NOW + 3).result());
        assertEquals(2, navigation.homes(ALICE).size());
        assertEquals(8, navigation.home(ALICE, "house").orElseThrow().x());
        assertEquals(NavigationResult.SUCCESS, navigation.deleteHome(ALICE, "office"));
        assertEquals(NavigationResult.HOME_NOT_FOUND, navigation.deleteHome(ALICE, "office"));
    }

    @Test
    void civicWarpsAreRestartSafeAndCaseInsensitive() throws Exception {
        assertEquals(NavigationResult.SUCCESS,
                navigation.setWarp(location("Spawn", 10, 64, 20), ALICE, NOW).result());

        NavigationRepository restarted = new NavigationRepository(database);
        assertEquals(10, restarted.warp("SPAWN").orElseThrow().x());
        assertEquals(1, restarted.warps().size());
        assertEquals(NavigationResult.SUCCESS, restarted.deleteWarp("spawn"));
        assertTrue(restarted.warps().isEmpty());
    }

    @Test
    void invalidAndUnknownLocationsDoNotMutateState() throws Exception {
        SavedLocation invalid = new SavedLocation("not valid", "world", 0, 64, 0, 0, 0);
        assertEquals(NavigationResult.INVALID_NAME,
                navigation.setHome(ALICE, invalid, 3, NOW).result());
        assertEquals(NavigationResult.CITIZEN_NOT_FOUND,
                navigation.setHome(UUID.randomUUID(), location("home", 0, 64, 0), 3, NOW).result());
        assertEquals(NavigationResult.WARP_NOT_FOUND, navigation.deleteWarp("missing"));
        assertTrue(navigation.homes(ALICE).isEmpty());
    }

    private static SavedLocation location(String id, double x, double y, double z) {
        return new SavedLocation(id, "world", x, y, z, 90, 0);
    }
}
