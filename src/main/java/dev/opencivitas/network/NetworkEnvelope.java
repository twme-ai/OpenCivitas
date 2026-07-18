package dev.opencivitas.network;

import dev.opencivitas.chat.ChatChannel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public record NetworkEnvelope(
        UUID messageId,
        String sourceNode,
        String sourceDisplayName,
        UUID playerId,
        String playerName,
        ChatChannel channel,
        String content,
        long createdAt
) {
    private static final int MAGIC = 0x4f43564e;
    private static final int VERSION = 1;
    private static final int MAXIMUM_PACKET_BYTES = 4_096;
    private static final Pattern NODE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");

    public NetworkEnvelope {
        if (messageId == null || playerId == null || channel == null) {
            throw new IllegalArgumentException("Network envelope identifiers and channel are required");
        }
        sourceNode = checked(sourceNode, 48, "source node").toLowerCase(Locale.ROOT);
        if (!NODE_ID.matcher(sourceNode).matches()) throw new IllegalArgumentException("Invalid source node");
        sourceDisplayName = checked(sourceDisplayName, 64, "source display name");
        playerName = checked(playerName, 32, "player name");
        content = checked(content, 500, "content");
        if (channel == ChatChannel.LOCAL || channel == ChatChannel.MURMUR) {
            throw new IllegalArgumentException("Proximity messages cannot use the network envelope");
        }
        if (createdAt <= 0) throw new IllegalArgumentException("Invalid message timestamp");
    }

    public String encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeByte(VERSION);
                output.writeLong(messageId.getMostSignificantBits());
                output.writeLong(messageId.getLeastSignificantBits());
                write(output, sourceNode);
                write(output, sourceDisplayName);
                output.writeLong(playerId.getMostSignificantBits());
                output.writeLong(playerId.getLeastSignificantBits());
                write(output, playerName);
                write(output, channel.name().toLowerCase(Locale.ROOT));
                write(output, content);
                output.writeLong(createdAt);
            }
            byte[] packet = bytes.toByteArray();
            if (packet.length > MAXIMUM_PACKET_BYTES) throw new IllegalArgumentException("Network packet is too large");
            return Base64.getUrlEncoder().withoutPadding().encodeToString(packet);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode network envelope", exception);
        }
    }

    public static NetworkEnvelope decode(String encoded) {
        if (encoded == null || encoded.length() > MAXIMUM_PACKET_BYTES * 2) {
            throw new IllegalArgumentException("Invalid network packet size");
        }
        byte[] packet;
        try {
            packet = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid network packet encoding", exception);
        }
        if (packet.length > MAXIMUM_PACKET_BYTES) throw new IllegalArgumentException("Network packet is too large");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(packet))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
                throw new IllegalArgumentException("Unsupported network packet");
            }
            UUID messageId = new UUID(input.readLong(), input.readLong());
            String sourceNode = read(input, 48);
            String sourceDisplayName = read(input, 64);
            UUID playerId = new UUID(input.readLong(), input.readLong());
            String playerName = read(input, 32);
            ChatChannel channel;
            try {
                channel = ChatChannel.valueOf(read(input, 16).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown network chat channel", exception);
            }
            String content = read(input, 500);
            long createdAt = input.readLong();
            if (input.available() != 0) throw new IllegalArgumentException("Trailing network packet data");
            return new NetworkEnvelope(messageId, sourceNode, sourceDisplayName, playerId,
                    playerName, channel, content, createdAt);
        } catch (EOFException exception) {
            throw new IllegalArgumentException("Truncated network packet", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid network packet", exception);
        }
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String read(DataInputStream input, int maximumCharacters) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > maximumCharacters * 4 || length > input.available()) {
            throw new IllegalArgumentException("Invalid network packet text length");
        }
        String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(input.readNBytes(length))).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Invalid network packet UTF-8", exception);
        }
        if (value.length() > maximumCharacters) throw new IllegalArgumentException("Network packet text is too long");
        return value;
    }

    private static String checked(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }
}
