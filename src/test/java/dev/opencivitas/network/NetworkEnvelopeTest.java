package dev.opencivitas.network;

import dev.opencivitas.chat.ChatChannel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkEnvelopeTest {
    private static final UUID MESSAGE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final long NOW = 2_000_000_000_000L;

    @Test
    void versionedEnvelopeRoundTripsStableIdentityAndUnicodeContent() {
        NetworkEnvelope original = new NetworkEnvelope(MESSAGE, "north-city", "North City",
                PLAYER, "Alice", ChatChannel.SENATE, "Budget review at 會議室", NOW);

        NetworkEnvelope decoded = NetworkEnvelope.decode(original.encode());

        assertEquals(original, decoded);
        assertEquals(PLAYER, decoded.playerId());
        assertEquals("north-city", decoded.sourceNode());
    }

    @Test
    void proximityAndMalformedPacketsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> envelope(ChatChannel.LOCAL, "hello"));
        assertThrows(IllegalArgumentException.class, () -> envelope(ChatChannel.MURMUR, "hello"));
        assertThrows(IllegalArgumentException.class, () -> envelope(ChatChannel.GLOBAL, "line\nbreak"));
        assertThrows(IllegalArgumentException.class, () -> envelope(ChatChannel.GLOBAL, "x".repeat(501)));
        assertThrows(IllegalArgumentException.class, () -> NetworkEnvelope.decode("not-base64!"));

        byte[] encoded = Base64.getUrlDecoder().decode(envelope(ChatChannel.GLOBAL, "hello").encode());
        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IllegalArgumentException.class, () -> NetworkEnvelope.decode(
                Base64.getUrlEncoder().withoutPadding().encodeToString(trailing)));
    }

    @Test
    void replayFilterRejectsDuplicatesAndImplausibleTimestamps() {
        NetworkDeduplicator filter = new NetworkDeduplicator(
                Duration.ofMinutes(2), Duration.ofSeconds(30), 2);

        assertTrue(filter.accept(MESSAGE, NOW, NOW));
        assertFalse(filter.accept(MESSAGE, NOW, NOW));
        assertFalse(filter.accept(UUID.randomUUID(), NOW - Duration.ofMinutes(2).toMillis() - 1, NOW));
        assertFalse(filter.accept(UUID.randomUUID(), NOW + Duration.ofSeconds(30).toMillis() + 1, NOW));
        assertTrue(filter.accept(UUID.randomUUID(), NOW + 1, NOW));
        assertTrue(filter.accept(UUID.randomUUID(), NOW + 2, NOW));
        assertTrue(filter.accept(MESSAGE, NOW + 3, NOW));
    }

    private static NetworkEnvelope envelope(ChatChannel channel, String content) {
        return new NetworkEnvelope(MESSAGE, "city", "City", PLAYER, "Alice", channel, content, NOW);
    }
}
