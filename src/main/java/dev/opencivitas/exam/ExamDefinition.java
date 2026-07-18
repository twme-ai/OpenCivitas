package dev.opencivitas.exam;

import java.util.List;

public record ExamDefinition(
        String id,
        String qualification,
        int passingScore,
        boolean randomizeQuestions,
        LocalizedText title,
        LocalizedText description,
        List<ExamQuestion> questions
) {
    public ExamDefinition {
        questions = List.copyOf(questions);
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("Exam must contain at least one question");
        }
        if (passingScore < 1 || passingScore > questions.size()) {
            throw new IllegalArgumentException("Passing score must be between 1 and the question count");
        }
    }
}
