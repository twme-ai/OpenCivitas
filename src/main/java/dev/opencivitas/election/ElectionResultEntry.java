package dev.opencivitas.election;

import java.math.BigDecimal;

public record ElectionResultEntry(
        String choiceId,
        int placement,
        boolean elected,
        BigDecimal finalTally
) {
}
