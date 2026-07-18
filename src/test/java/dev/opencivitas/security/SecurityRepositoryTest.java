package dev.opencivitas.security;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityRepositoryTest {
    private static final UUID ALICE = id(1);
    private static final UUID BOB = id(2);
    private static final long NOW = 2_000_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private SecurityRepository security;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = SecurityRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        CitizenRepository citizens = new CitizenRepository(database);
        citizens.register(ALICE, "Alice", "en_US", 0);
        citizens.register(BOB, "Bob", "en_US", 0);
        security = new SecurityRepository(database, 2, 2, 2);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void camerasGroupsAndMembershipsAreOwnedAndOrdered() throws Exception {
        SecurityCamera first = camera(0, 0, 0, NOW);
        SecurityCamera second = camera(1, 0, 0, NOW + 1);
        assertEquals("camera-1", first.name());
        assertEquals("camera-2", second.name());
        assertEquals(SecurityResult.LOCATION_OCCUPIED,
                security.placeCamera(BOB, "world", 0, 0, 0, 0, 0, NOW + 2).result());
        assertEquals(SecurityResult.LIMIT_REACHED,
                security.placeCamera(ALICE, "world", 2, 0, 0, 0, 0, NOW + 3).result());

        CameraGroup group = security.createGroup(ALICE, "front-door", NOW + 3).value().orElseThrow();
        assertEquals(SecurityResult.NAME_TAKEN,
                security.createGroup(ALICE, "FRONT-DOOR", NOW + 4).result());
        assertEquals(SecurityResult.SUCCESS,
                security.addCamera(ALICE, group.name(), first.name(), NOW + 5));
        assertEquals(SecurityResult.SUCCESS,
                security.addCamera(ALICE, group.name(), second.name(), NOW + 6));
        assertEquals(SecurityResult.ALREADY_MEMBER,
                security.addCamera(ALICE, group.name(), first.name(), NOW + 7));
        assertEquals(SecurityResult.GROUP_NOT_FOUND,
                security.addCamera(BOB, group.name(), first.name(), NOW + 8));

        SecurityComputer computer = security.placeComputer(
                ALICE, "world", 5, 64, 5, NOW + 9).value().orElseThrow();
        SecurityComputer assigned = security.assignGroup(
                ALICE, computer.id(), group.name()).value().orElseThrow();
        assertEquals(group.id(), assigned.groupId());
        ComputerDashboard dashboard = security.dashboard(computer.id()).orElseThrow();
        assertEquals(2, dashboard.cameras().size());
        assertEquals(first.id(), dashboard.cameras().getFirst().id());

        assertEquals(SecurityResult.SUCCESS,
                security.removeCamera(ALICE, group.name(), first.name()));
        assertEquals(SecurityResult.NOT_MEMBER,
                security.removeCamera(ALICE, group.name(), first.name()));
        assertEquals(second.id(), security.dashboard(computer.id()).orElseThrow().cameras().getFirst().id());
    }

    @Test
    void computerAccessIsPrivateByDefaultAndPublicIsExplicit() throws Exception {
        SecurityComputer computer = security.placeComputer(
                ALICE, "world", 5, 64, 5, NOW).value().orElseThrow();
        assertEquals(SecurityResult.LOCATION_OCCUPIED,
                security.placeComputer(BOB, "world", 5, 64, 5, NOW + 1).result());
        assertTrue(security.canAccess(computer.id(), ALICE));
        assertFalse(security.canAccess(computer.id(), BOB));

        assertEquals(SecurityResult.SUCCESS, security.grantAccess(ALICE, computer.id(), BOB, NOW + 2));
        assertEquals(SecurityResult.ACCESS_EXISTS, security.grantAccess(ALICE, computer.id(), BOB, NOW + 3));
        assertTrue(security.canAccess(computer.id(), BOB));
        assertEquals("Bob", security.dashboard(computer.id()).orElseThrow().access().getFirst().playerName());
        assertEquals(SecurityResult.SUCCESS, security.revokeAccess(ALICE, computer.id(), BOB));
        assertFalse(security.canAccess(computer.id(), BOB));

        SecurityComputer publicComputer = security.togglePublic(ALICE, computer.id()).value().orElseThrow();
        assertTrue(publicComputer.publicAccess());
        assertTrue(security.canAccess(computer.id(), BOB));
        assertEquals(SecurityResult.NOT_OWNER, security.togglePublic(BOB, computer.id()).result());
    }

    @Test
    void renameRotateAndDeletesCleanDependentState() throws Exception {
        SecurityCamera camera = camera(0, 0, 0, NOW);
        CameraGroup group = security.createGroup(ALICE, "office", NOW + 1).value().orElseThrow();
        assertEquals(SecurityResult.SUCCESS,
                security.addCamera(ALICE, group.name(), camera.name(), NOW + 2));
        SecurityComputer computer = security.placeComputer(
                ALICE, "world", 2, 64, 2, NOW + 3).value().orElseThrow();
        security.assignGroup(ALICE, computer.id(), group.name());

        SecurityCamera renamed = security.renameCamera(
                ALICE, camera.name(), "vault").value().orElseThrow();
        SecurityCamera rotated = security.rotateCamera(ALICE, renamed.id(), 30, 90).value().orElseThrow();
        assertEquals("vault", rotated.name());
        assertEquals(30, rotated.yaw());
        assertEquals(45, rotated.pitch());

        assertEquals(SecurityResult.SUCCESS, security.deleteCamera(ALICE, camera.id()).result());
        assertTrue(security.dashboard(computer.id()).orElseThrow().cameras().isEmpty());
        assertEquals(SecurityResult.SUCCESS, security.deleteGroup(ALICE, group.name()));
        assertTrue(security.dashboard(computer.id()).orElseThrow().group().isEmpty());
        assertEquals(SecurityResult.SUCCESS, security.deleteComputer(ALICE, computer.id()).result());
        assertTrue(security.dashboard(computer.id()).isEmpty());
    }

    private SecurityCamera camera(double x, double y, double z, long now) throws Exception {
        return security.placeCamera(ALICE, "world", x, y, z, 0, 0, now).value().orElseThrow();
    }

    private static UUID id(long suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
