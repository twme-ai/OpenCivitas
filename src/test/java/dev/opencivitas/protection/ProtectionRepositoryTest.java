package dev.opencivitas.protection;

import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectionRepositoryTest {
    private static final UUID ALICE = id(1);
    private static final UUID BOB = id(2);
    private static final long NOW = 2_000_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private ProtectionRepository protections;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = ProtectionRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        CitizenRepository citizens = new CitizenRepository(database);
        citizens.register(ALICE, "Alice", "en_US", 0);
        citizens.register(BOB, "Bob", "en_US", 0);
        protections = new ProtectionRepository(database, 2, 2, 2);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void protectionAclTransferAndDeletionArePersistent() throws Exception {
        ProtectionKey key = new ProtectionKey("world", 1, 64, 2);
        BlockProtection created = protections.create(
                ALICE, key, ProtectionType.PRIVATE, NOW).value().orElseThrow();
        assertEquals("Alice", created.ownerName());
        assertEquals(ProtectionResult.ALREADY_PROTECTED,
                protections.create(BOB, key, ProtectionType.PUBLIC, NOW + 1).result());

        ProtectionSource bob = new ProtectionSource(ProtectionSourceType.PLAYER, BOB.toString());
        BlockProtection shared = protections.modifyAccess(
                key, bob, ProtectionAccess.ADMIN, true, NOW + 2).value().orElseThrow();
        assertEquals(ProtectionAccess.ADMIN, shared.access().get(bob));
        assertEquals(ProtectionAccess.ADMIN,
                protections.loadState().protections().getFirst().access().get(bob));

        BlockProtection transferred = protections.transfer(key, BOB).value().orElseThrow();
        assertEquals(BOB, transferred.ownerId());
        assertEquals("Bob", transferred.ownerName());
        assertTrue(transferred.access().isEmpty());
        assertEquals(ProtectionResult.SUCCESS, protections.delete(key).result());
        assertTrue(protections.loadState().protections().isEmpty());
    }

    @Test
    void groupsAndOwnerTrustResolveAfterRestart() throws Exception {
        ProtectionKey key = new ProtectionKey("world", 3, 64, 4);
        protections.create(ALICE, key, ProtectionType.PRIVATE, NOW);
        ProtectionGroup group = protections.createGroup(ALICE, "staff", NOW + 1).value().orElseThrow();
        assertTrue(group.members().isEmpty());
        group = protections.modifyGroup(ALICE, "staff", BOB, true, NOW + 2).value().orElseThrow();
        assertTrue(group.members().contains(BOB));

        ProtectionSource source = new ProtectionSource(ProtectionSourceType.GROUP, "staff");
        Map<ProtectionSource, ProtectionAccess> trust = protections.modifyTrust(
                ALICE, source, ProtectionAccess.ADMIN, true, NOW + 3).value().orElseThrow();
        assertEquals(ProtectionAccess.ADMIN, trust.get(source));

        ProtectionRegistry registry = new ProtectionRegistry();
        ProtectionState state = protections.loadState();
        registry.replaceAll(state);
        BlockProtection protection = state.protections().getFirst();
        assertEquals(ProtectionAccess.ADMIN,
                registry.effectiveAccess(protection, BOB, ignored -> false, ignored -> false));

        protections.modifyGroup(ALICE, "staff", BOB, false, NOW + 4);
        ProtectionGroup withoutBob = protections.loadState().groups().getFirst();
        assertFalse(withoutBob.members().contains(BOB));
        assertEquals(ProtectionResult.SUCCESS, protections.deleteGroup(ALICE, "staff").result());
        assertTrue(protections.loadState().groups().isEmpty());
        assertTrue(protections.loadState().trust().getOrDefault(ALICE, Map.of()).isEmpty());
    }

    @Test
    void configuredProtectionAndGroupLimitsAreEnforced() throws Exception {
        assertEquals(ProtectionResult.SUCCESS, protections.create(
                ALICE, new ProtectionKey("world", 0, 64, 0), ProtectionType.PRIVATE, NOW).result());
        assertEquals(ProtectionResult.SUCCESS, protections.create(
                ALICE, new ProtectionKey("world", 1, 64, 0), ProtectionType.PRIVATE, NOW).result());
        assertEquals(ProtectionResult.LIMIT_REACHED, protections.create(
                ALICE, new ProtectionKey("world", 2, 64, 0), ProtectionType.PRIVATE, NOW).result());

        protections.createGroup(ALICE, "one", NOW);
        protections.createGroup(ALICE, "two", NOW);
        assertEquals(ProtectionResult.LIMIT_REACHED, protections.createGroup(ALICE, "three", NOW).result());
    }

    private static UUID id(long suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
