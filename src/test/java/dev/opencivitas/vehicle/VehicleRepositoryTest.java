package dev.opencivitas.vehicle;

import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.job.JobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleRepositoryTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID VEHICLE = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private VehicleRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = VehicleRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        CitizenRepository citizens = new CitizenRepository(database);
        citizens.register(ALICE, "Alice", "en_US", 0);
        citizens.register(BOB, "Bob", "en_US", 0);
        repository = new VehicleRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void creationEnforcesOwnerLimit() throws Exception {
        assertEquals(VehicleResult.SUCCESS, repository.create(state(VEHICLE, ALICE), 1).result());

        VehicleResult second = repository.create(state(UUID.randomUUID(), ALICE), 1).result();

        assertEquals(VehicleResult.OWNER_LIMIT_REACHED, second);
        assertEquals(1, repository.owned(ALICE).size());
    }

    @Test
    void creationRestoresSerializedTrunkData() throws Exception {
        byte[] contents = {9, 8, 7, 6};

        assertEquals(VehicleResult.SUCCESS,
                repository.create(state(VEHICLE, ALICE), 6, contents).result());

        assertArrayEquals(contents, repository.storage(VEHICLE));
    }

    @Test
    void lockAndFuelRequireOwnershipAndFuelIsBounded() throws Exception {
        repository.create(state(VEHICLE, ALICE), 6);

        assertEquals(VehicleResult.NOT_OWNER,
                repository.setLocked(VEHICLE, BOB, false, 2_000).result());
        VehicleState unlocked = repository.setLocked(VEHICLE, ALICE, false, 2_000).value();
        VehicleState fueled = repository.refuel(VEHICLE, ALICE, 4_000, 5_000, 3_000).value();
        VehicleState capped = repository.refuel(VEHICLE, ALICE, 4_000, 5_000, 4_000).value();

        assertFalse(unlocked.locked());
        assertEquals(4_000, fueled.fuel());
        assertEquals(5_000, capped.fuel());
        assertEquals(VehicleResult.FUEL_FULL,
                repository.refuel(VEHICLE, ALICE, 1, 5_000, 5_000).result());
    }

    @Test
    void mechanicCanRepairAnotherCitizensVehicle() throws Exception {
        VehicleState damaged = state(VEHICLE, ALICE).withTelemetry(
                "world", 1, 65, 1, 0, 0, 25, Instant.ofEpochMilli(2_000));
        repository.create(damaged, 6);

        assertEquals(VehicleResult.NOT_OWNER,
                repository.repair(VEHICLE, BOB, 40, 100, 3_000).result());
        new JobRepository(database).grantQualification(BOB, "mechanic", null);
        VehicleState repaired = repository.repair(VEHICLE, BOB, 40, 100, 3_000).value();

        assertEquals(65, repaired.health());
    }

    @Test
    void transferChangesOwnerAndAppliesRecipientLimit() throws Exception {
        repository.create(state(VEHICLE, ALICE), 6);
        repository.create(state(UUID.randomUUID(), BOB), 6);

        assertEquals(VehicleResult.SELF_TRANSFER,
                repository.transfer(VEHICLE, ALICE, ALICE, 6, 1_500).result());
        assertEquals(VehicleResult.OWNER_LIMIT_REACHED,
                repository.transfer(VEHICLE, ALICE, BOB, 1, 2_000).result());
        VehicleState transferred = repository.transfer(VEHICLE, ALICE, BOB, 2, 3_000).value();

        assertEquals(BOB, transferred.ownerId());
        assertEquals("Bob", transferred.ownerName());
        assertTrue(transferred.locked());
    }

    @Test
    void telemetryAndPickupPersistWholeVehicleState() throws Exception {
        repository.create(state(VEHICLE, ALICE), 6);
        VehicleState moved = state(VEHICLE, ALICE).withTelemetry(
                "city", 11.5, 72, -8.25, 90, 1_234, 77, Instant.ofEpochMilli(5_000));

        repository.updateTelemetry(moved);
        VehicleState loaded = repository.find(VEHICLE).orElseThrow();
        repository.saveStorage(VEHICLE, new byte[]{1, 2, 3}, 5_500);
        VehicleOperation<VehiclePickup> removed = repository.remove(VEHICLE, ALICE);

        assertEquals("city", loaded.worldName());
        assertEquals(11.5, loaded.x());
        assertEquals(1_234, loaded.fuel());
        assertEquals(77, removed.value().state().health());
        assertEquals(3, removed.value().storage().length);
        assertTrue(repository.find(VEHICLE).isEmpty());
    }

    @Test
    void accessUsesMechanicQualificationAndUnexpiredLicenses() throws Exception {
        JobRepository jobs = new JobRepository(database);
        jobs.grantQualification(ALICE, "mechanic", null);
        jobs.grantQualification(ALICE, "pilot", null);
        jobs.grantLicense(ALICE, "driver", null, null, 1_000);
        jobs.grantLicense(ALICE, "pilot", null, 1_500L, 1_000);

        VehicleAccess access = repository.access(ALICE, 2_000);

        assertTrue(access.mechanic());
        assertTrue(access.hasLicense("driver"));
        assertTrue(access.hasLicense("pilot"));
    }

    private static VehicleState state(UUID id, UUID owner) {
        long now = 1_000;
        return new VehicleState(id, "compact-car", owner, owner.equals(ALICE) ? "Alice" : "Bob",
                "world", 1, 65, 1, 0, 0, true, 100,
                Instant.ofEpochMilli(now), Instant.ofEpochMilli(now));
    }
}
