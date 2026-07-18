package dev.opencivitas.exam;

import java.time.Instant;

public record ExamAttempt(
        long id,
        String examId,
        String qualification,
        int score,
        int total,
        boolean passed,
        Instant completedAt
) {
}
