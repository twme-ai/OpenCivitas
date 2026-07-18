package dev.opencivitas.family;

import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
import dev.opencivitas.navigation.SavedLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FamilyRepositoryTest {
    private static final UUID ALICE = id(1);
    private static final UUID BOB = id(2);
    private static final UUID CAROL = id(3);
    private static final UUID LAWYER = id(4);
    private static final long NOW = 2_000_000_000_000L;
    private static final FamilyPolicy POLICY = new FamilyPolicy(
            Duration.ofDays(3), Duration.ofDays(7), false,
            List.of("lawyer"), List.of("lawyer"));

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private FamilyRepository family;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = FamilyRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        CitizenRepository citizens = new CitizenRepository(database);
        citizens.register(ALICE, "Alice", "en_US", 0);
        citizens.register(BOB, "Bob", "en_US", 0);
        citizens.register(CAROL, "Carol", "en_US", 0);
        citizens.register(LAWYER, "Lawyer", "en_US", 0);
        job(LAWYER, "lawyer");
        family = new FamilyRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void friendRequestNeedsTargetAcceptanceAndCreatesOneNormalizedPair() throws Exception {
        assertEquals(FamilyResult.SUCCESS,
                family.requestFriend(ALICE, BOB, POLICY.friendRequestExpiry(), NOW));
        assertEquals(FamilyResult.REQUEST_ALREADY_PENDING,
                family.requestFriend(BOB, ALICE, POLICY.friendRequestExpiry(), NOW + 1));
        assertEquals(FamilyResult.SUCCESS, family.respondFriend(BOB, ALICE, true, NOW + 2));

        assertEquals(List.of("Bob"), family.friends(ALICE).stream().map(Friendship::friendName).toList());
        assertEquals(List.of("Alice"), family.friends(BOB).stream().map(Friendship::friendName).toList());
        assertEquals(FamilyResult.ALREADY_FRIENDS,
                family.requestFriend(ALICE, BOB, POLICY.friendRequestExpiry(), NOW + 3));
        assertEquals(FamilyResult.SUCCESS, family.removeFriend(BOB, ALICE));
        assertTrue(family.friends(ALICE).isEmpty());
    }

    @Test
    void expiredFriendAndMarriageRequestsCannotBeAccepted() throws Exception {
        family.requestFriend(ALICE, BOB, Duration.ofMillis(1), NOW);
        assertEquals(FamilyResult.REQUEST_NOT_FOUND, family.respondFriend(BOB, ALICE, true, NOW + 1));
        family.proposeMarriage(ALICE, BOB, Duration.ofMillis(1), NOW + 2);
        assertEquals(FamilyResult.PROPOSAL_NOT_FOUND,
                family.respondMarriage(BOB, ALICE, true, NOW + 3));
    }

    @Test
    void mutualConsentAndLawyerAreBothRequiredToOfficiate() throws Exception {
        family.proposeMarriage(ALICE, BOB, POLICY.marriageProposalExpiry(), NOW);
        assertEquals(FamilyResult.PROPOSAL_NOT_ACCEPTED,
                family.officiate(LAWYER, ALICE, BOB, POLICY, NOW + 1).result());
        family.respondMarriage(BOB, ALICE, true, NOW + 2);
        assertEquals(FamilyResult.NOT_LAWYER,
                family.officiate(CAROL, ALICE, BOB, POLICY, NOW + 3).result());

        Marriage marriage = family.officiate(LAWYER, ALICE, BOB, POLICY, NOW + 4)
                .value().orElseThrow();

        assertEquals(BOB, marriage.partnerId(ALICE));
        assertFalse(marriage.partnerPvpAllowed());
        assertEquals(FamilyResult.ALREADY_MARRIED,
                family.proposeMarriage(CAROL, BOB, POLICY.marriageProposalExpiry(), NOW + 5));
        assertEquals(marriage.id(), new FamilyRepository(database).activeMarriage(ALICE).orElseThrow().id());
    }

    @Test
    void partnerHomePvpConsentAndDivorceRemainAuditable() throws Exception {
        family.proposeMarriage(ALICE, BOB, POLICY.marriageProposalExpiry(), NOW);
        family.respondMarriage(BOB, ALICE, true, NOW + 1);
        Marriage marriage = family.officiate(LAWYER, ALICE, BOB, POLICY, NOW + 2)
                .value().orElseThrow();

        assertFalse(family.setPartnerPvp(ALICE, true, NOW + 3).value().orElseThrow().partnerPvpAllowed());
        assertTrue(family.setPartnerPvp(BOB, true, NOW + 4).value().orElseThrow().partnerPvpAllowed());
        SavedLocation home = new SavedLocation("partner-home", "world", 1, 65, 2, 0, 0);
        assertEquals(home, family.setHome(ALICE, home, NOW + 5).value().orElseThrow().home());

        assertEquals(marriage.id(), family.divorce(BOB, "Mutual separation", NOW + 6)
                .value().orElseThrow().id());
        assertTrue(family.activeMarriage(ALICE).isEmpty());
        assertEquals(FamilyResult.NOT_MARRIED,
                family.setPartnerPvp(ALICE, false, NOW + 7).result());
    }

    @Test
    void selfTargetsAndUnknownCitizensAreRejected() throws Exception {
        assertEquals(FamilyResult.CANNOT_TARGET_SELF,
                family.requestFriend(ALICE, ALICE, POLICY.friendRequestExpiry(), NOW));
        assertEquals(FamilyResult.CANNOT_TARGET_SELF,
                family.proposeMarriage(ALICE, ALICE, POLICY.marriageProposalExpiry(), NOW));
        assertEquals(FamilyResult.CITIZEN_NOT_FOUND,
                family.requestFriend(ALICE, id(99), POLICY.friendRequestExpiry(), NOW));
    }

    private void job(UUID player, String job) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO citizen_jobs(player_uuid, job_id, category, joined_at, appointed_by)
                     VALUES (?, ?, 'PROFESSION', ?, NULL)
                     """)) {
            statement.setString(1, player.toString());
            statement.setString(2, job);
            statement.setLong(3, NOW);
            statement.executeUpdate();
        }
    }

    private static UUID id(long suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
