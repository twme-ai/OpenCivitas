package dev.opencivitas.election;

import java.time.Instant;
import java.util.UUID;

public record OfficeTerm(
        long id,
        String officeId,
        int seatNumber,
        UUID holderId,
        String holderName,
        Long electionId,
        Instant startedAt,
        Instant endsAt
) {
}
