package dev.opencivitas.chat;

import dev.opencivitas.citizen.CitizenRepository;
import dev.opencivitas.database.Database;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRepositoryTest {
    private static final UUID ALICE = id(1);
    private static final UUID BOB = id(2);
    private static final UUID CAROL = id(3);
    private static final long NOW = 2_000_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    private Database database;
    private ChatRepository chat;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(temporaryDirectory.resolve("test.db"));
        try (InputStream schema = ChatRepositoryTest.class.getResourceAsStream("/schema.sql")) {
            assertNotNull(schema);
            database.initialize(schema);
        }
        CitizenRepository citizens = new CitizenRepository(database);
        citizens.register(ALICE, "Alice", "en_US", 0);
        citizens.register(BOB, "Bob", "en_US", 0);
        citizens.register(CAROL, "Carol", "en_US", 0);
        chat = new ChatRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void channelPreferenceAndReplyContactPersistWithoutMessageContent() throws Exception {
        assertEquals(ChatChannel.GLOBAL, chat.preference(ALICE));
        assertEquals(ChatResult.SUCCESS, chat.setPreference(ALICE, ChatChannel.LOCAL, NOW));
        assertEquals(ChatChannel.LOCAL, new ChatRepository(database).preference(ALICE));
        assertEquals(ChatResult.CITIZEN_NOT_FOUND,
                chat.setPreference(id(99), ChatChannel.GLOBAL, NOW));

        assertEquals(ChatResult.SUCCESS, chat.touchConversation(ALICE, BOB, NOW + 1));
        assertEquals(BOB, chat.replyTarget(ALICE).orElseThrow());
        assertEquals(ALICE, chat.replyTarget(BOB).orElseThrow());
        assertEquals(ChatResult.CANNOT_MESSAGE_SELF, chat.touchConversation(ALICE, ALICE, NOW + 2));
    }

    @Test
    void offlineMailIsPrivateReadableAndSoftDeletedByItsRecipient() throws Exception {
        MailMessage sent = chat.sendMail(ALICE, BOB, "Meet at court", 500, NOW)
                .value().orElseThrow();

        assertEquals(1, chat.unreadMail(BOB));
        assertNull(chat.inbox(BOB, 10, 0).getFirst().readAt());
        assertTrue(chat.inbox(CAROL, 10, 0).isEmpty());
        assertEquals(ChatResult.MAIL_NOT_FOUND, chat.readMail(CAROL, sent.id(), NOW + 1).result());
        assertNotNull(chat.readMail(BOB, sent.id(), NOW + 2).value().orElseThrow().readAt());
        assertEquals(0, chat.unreadMail(BOB));
        assertEquals(ChatResult.SUCCESS, chat.deleteMail(BOB, sent.id(), NOW + 3));
        assertTrue(chat.inbox(BOB, 10, 0).isEmpty());
        assertEquals(ChatResult.CANNOT_MESSAGE_SELF,
                chat.sendMail(ALICE, ALICE, "note", 500, NOW + 4).result());
    }

    @Test
    void advertisementRequiresQualificationAndEnforcesCooldownAtomically() throws Exception {
        assertEquals(ChatResult.MISSING_QUALIFICATION, chat.submitAdvertisement(
                ALICE, "Fresh bread", 500, Duration.ofMinutes(10), NOW).result());
        qualify(ALICE, "entrepreneur");

        assertEquals(ChatResult.SUCCESS, chat.submitAdvertisement(
                ALICE, "Fresh bread", 500, Duration.ofMinutes(10), NOW + 1).result());
        AdvertisementAttempt cooldown = chat.submitAdvertisement(
                ALICE, "More bread", 500, Duration.ofMinutes(10), NOW + 2);
        assertEquals(ChatResult.COOLDOWN, cooldown.result());
        assertEquals(Duration.ofMinutes(10).toMillis() - 1, cooldown.remainingCooldownMillis());
        assertEquals(ChatResult.SUCCESS, chat.submitAdvertisement(
                ALICE, "More bread", 500, Duration.ofMinutes(10),
                NOW + 1 + Duration.ofMinutes(10).toMillis()).result());
    }

    @Test
    void departmentMembershipCombinesCurrentJobsAndUnexpiredOffices() throws Exception {
        job(ALICE, "prosecutor");
        office(BOB, "senator", NOW - 1, NOW + 100);
        DepartmentChannelDefinition doj = new DepartmentChannelDefinition(
                ChatChannel.DOJ, List.of("prosecutor"), List.of());
        DepartmentChannelDefinition senate = new DepartmentChannelDefinition(
                ChatChannel.SENATE, List.of(), List.of("senator"));

        assertTrue(chat.isDepartmentMember(ALICE, doj, NOW));
        assertFalse(chat.isDepartmentMember(CAROL, doj, NOW));
        assertTrue(chat.isDepartmentMember(BOB, senate, NOW));
        assertFalse(chat.isDepartmentMember(BOB, senate, NOW + 101));
    }

    private void qualify(UUID player, String qualification) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO qualifications(player_uuid, qualification_id, granted_by, granted_at)
                     VALUES (?, ?, NULL, ?)
                     """)) {
            statement.setString(1, player.toString());
            statement.setString(2, qualification);
            statement.setLong(3, NOW);
            statement.executeUpdate();
        }
    }

    private void job(UUID player, String job) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO citizen_jobs(player_uuid, job_id, category, joined_at, appointed_by)
                     VALUES (?, ?, 'GOVERNMENT', ?, NULL)
                     """)) {
            statement.setString(1, player.toString());
            statement.setString(2, job);
            statement.setLong(3, NOW);
            statement.executeUpdate();
        }
    }

    private void office(UUID player, String office, long starts, long ends) throws Exception {
        try (Connection connection = database.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO office_terms(office_id, seat_number, holder_uuid, election_id,
                                              started_at, ends_at, status)
                     VALUES (?, 1, ?, NULL, ?, ?, 'ACTIVE')
                     """)) {
            statement.setString(1, office);
            statement.setString(2, player.toString());
            statement.setLong(3, starts);
            statement.setLong(4, ends);
            statement.executeUpdate();
        }
    }

    private static UUID id(long suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
