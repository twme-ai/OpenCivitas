package dev.opencivitas.exam;

public record ExamAnswer(Status status, int answered, int total, int score) {
    public enum Status {
        INVALID_OPTION,
        NEXT_QUESTION,
        COMPLETE
    }
}
