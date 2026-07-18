package dev.opencivitas.chat;

import java.time.Instant;
import java.util.UUID;

public record MailMessage(
        long id,
        UUID senderId,
        String senderName,
        UUID recipientId,
        String content,
        Instant sentAt,
        Instant readAt
) {
}
