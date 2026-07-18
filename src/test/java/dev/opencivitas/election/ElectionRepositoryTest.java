package dev.opencivitas.election;

import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElectionRepositoryTest {
    private static final UUID ADMIN = uuid(1);
    private static final UUID ALICE = uuid(2);
    private static final UUID BOB = uuid(3);
    private static final UUID CAROL = uuid(4);
    private static final long NOW = 2_000_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private CitizenRepository citizens;
    private ElectionRepository elections;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = ElectionRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        citizens = new CitizenRepository(database);
        citizens.register(ADMIN, "Admin", "en_US", 0);
        citizens.register(ALICE, "Alice", "en_US", 0);
        citizens.register(BOB, "Bob", "en_US", 0);
        citizens.register(CAROL, "Carol", "en_US", 0);
        elections = new ElectionRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void rankedBallotCanBeReplacedButRemainsOneVote() throws Exception {
        Election election = createOffice(irv("mayor"), "mayor-election");
        nominate(election, ALICE, irv("mayor"));
        nominate(election, BOB, irv("mayor"));

        assertEquals(ElectionActionResult.SUCCESS,
                elections.castBallot(election.id(), CAROL, List.of(ALICE.toString(), BOB.toString()), NOW + 101)
                        .result());
        elections.castBallot(election.id(), CAROL, List.of(BOB.toString(), ALICE.toString()), NOW + 102);

        ElectionDetails details = elections.details(election.id()).orElseThrow();
        assertEquals(1, details.ballotCount());
        ElectionOperation closed = elections.close(election.id(), NOW + 200);
        assertEquals(List.of(BOB.toString()), closed.count().orElseThrow().winners());
    }

    @Test
    void closePersistsStvRoundsResultsAndOfficeTerms() throws Exception {
        ElectionDefinition council = definition("council", ElectionMethod.STV, 2, false);
        Election election = createOffice(council, "council-election");
        nominate(election, ALICE, council);
        nominate(election, BOB, council);
        nominate(election, CAROL, council);
        elections.castBallot(election.id(), ADMIN,
                List.of(ALICE.toString(), BOB.toString()), NOW + 101);
        elections.castBallot(election.id(), ALICE,
                List.of(ALICE.toString(), BOB.toString()), NOW + 101);
        elections.castBallot(election.id(), BOB,
                List.of(BOB.toString(), CAROL.toString()), NOW + 101);

        ElectionOperation closed = elections.close(election.id(), NOW + 200);

        assertEquals(ElectionActionResult.SUCCESS, closed.result());
        assertEquals(2, closed.count().orElseThrow().winners().size());
        assertEquals(3, elections.details(election.id()).orElseThrow().results().size());
        assertEquals(2, elections.activeTerms(NOW + 201).size());
        assertNotEquals(elections.activeTerms(NOW + 201).getFirst().seatNumber(),
                elections.activeTerms(NOW + 201).getLast().seatNumber());
    }

    @Test
    void referendumRequiresAStrictYesMajority() throws Exception {
        Election election = elections.createReferendum(
                ADMIN, "park", "Build a park?", NOW, NOW + 100).election().orElseThrow();
        elections.castBallot(election.id(), ALICE, List.of("yes"), NOW + 1);
        elections.castBallot(election.id(), BOB, List.of("no"), NOW + 1);

        ElectionOperation closed = elections.close(election.id(), NOW + 100);

        assertEquals(List.of("no"), closed.count().orElseThrow().winners());
        assertTrue(elections.activeTerms(NOW + 101).isEmpty());
    }

    @Test
    void eligibilityChecksTotalAndRecentTrackedPlaytime() throws Exception {
        ElectionDefinition definition = new ElectionDefinition(
                "president", ElectionMethod.IRV, 1, 120, Duration.ZERO,
                Duration.ofHours(4), Duration.ofHours(2), Duration.ofDays(30),
                false, null, false,
                Duration.ZERO, Duration.ZERO, Duration.ZERO, false);
        Election election = createOffice(definition, "presidential-election");
        activity(ALICE, NOW - Duration.ofDays(40).toMillis(), Duration.ofHours(5));

        assertEquals(ElectionActionResult.INELIGIBLE_RECENT_PLAYTIME,
                elections.nominate(election.id(), ALICE, null, definition, NOW + 1).result());

        activity(ALICE, NOW - Duration.ofHours(3).toMillis(), Duration.ofHours(3));
        assertEquals(ElectionActionResult.SUCCESS,
                elections.nominate(election.id(), ALICE, null, definition, NOW + 2).result());
    }

    @Test
    void runningMateAndImmediateReelectionRulesAreEnforced() throws Exception {
        ElectionDefinition president = new ElectionDefinition(
                "president", ElectionMethod.IRV, 1, 120, Duration.ZERO,
                Duration.ZERO, Duration.ZERO, Duration.ofDays(30),
                true, "vice-president", true,
                Duration.ZERO, Duration.ZERO, Duration.ZERO, true);
        Election first = createOffice(president, "president-one");
        assertEquals(ElectionActionResult.RUNNING_MATE_REQUIRED,
                elections.nominate(first.id(), ALICE, null, president, NOW + 1).result());
        assertEquals(ElectionActionResult.SUCCESS,
                elections.nominate(first.id(), ALICE, BOB, president, NOW + 1).result());
        elections.castBallot(first.id(), CAROL, List.of(ALICE.toString()), NOW + 101);
        elections.close(first.id(), NOW + 200);
        assertEquals(2, elections.activeTerms(NOW + 201).size());

        Election second = elections.createOffice(
                ADMIN, "president-two", president, NOW + 300, NOW + 400, NOW + 500)
                .election().orElseThrow();
        assertEquals(ElectionActionResult.INELIGIBLE_REELECTION,
                elections.nominate(second.id(), ALICE, CAROL, president, NOW + 301).result());
        assertEquals(ElectionActionResult.RUNNING_MATE_INELIGIBLE_HISTORY,
                elections.nominate(second.id(), CAROL, ALICE, president, NOW + 301).result());
    }

    @Test
    void invalidOrEarlyOperationsDoNotMutateElection() throws Exception {
        Election election = createOffice(irv("mayor"), "early");
        nominate(election, ALICE, irv("mayor"));

        assertEquals(ElectionActionResult.INVALID_PHASE,
                elections.castBallot(election.id(), BOB, List.of(ALICE.toString()), NOW + 50).result());
        assertEquals(ElectionActionResult.NOT_ENDED, elections.close(election.id(), NOW + 199).result());
        assertEquals(ElectionStatus.OPEN, elections.details(election.id()).orElseThrow().election().status());
    }

    private Election createOffice(ElectionDefinition definition, String slug) throws Exception {
        return elections.createOffice(ADMIN, slug, definition, NOW, NOW + 100, NOW + 200)
                .election().orElseThrow();
    }

    private void nominate(Election election, UUID candidate, ElectionDefinition definition) throws Exception {
        assertEquals(ElectionActionResult.SUCCESS,
                elections.nominate(election.id(), candidate, null, definition, NOW + 1).result());
    }

    private void activity(UUID player, long startedAt, Duration duration) throws Exception {
        citizens.startActivitySession(player, startedAt);
        citizens.endActivitySession(player, startedAt + duration.toMillis());
    }

    private static ElectionDefinition irv(String id) {
        return definition(id, ElectionMethod.IRV, 1, false);
    }

    private static ElectionDefinition definition(
            String id, ElectionMethod method, int seats, boolean runningMate) {
        return new ElectionDefinition(
                id, method, seats, 60, Duration.ZERO, Duration.ZERO, Duration.ZERO,
                Duration.ofDays(30), runningMate, runningMate ? "deputy" : null, false,
                Duration.ZERO, Duration.ZERO, Duration.ZERO, false);
    }

    private static UUID uuid(long suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
