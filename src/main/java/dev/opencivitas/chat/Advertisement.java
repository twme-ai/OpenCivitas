package dev.opencivitas.chat;

import java.time.Instant;
import java.util.UUID;

public record Advertisement(
        long id,
        UUID advertiserId,
        String advertiserName,
        String content,
        Instant createdAt
) {
}
