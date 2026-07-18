package dev.opencivitas.exam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

public final class ExamSession {
    private final ExamDefinition definition;
    private final List<ExamQuestion> questions;
    private int current;
    private int score;

    public ExamSession(ExamDefinition definition, RandomGenerator random) {
        this.definition = definition;
        List<ExamQuestion> selected = new ArrayList<>(definition.questions());
        if (definition.randomizeQuestions()) {
            Collections.shuffle(selected, new java.util.Random(random.nextLong()));
        }
        questions = List.copyOf(selected);
    }

    public ExamDefinition definition() {
        return definition;
    }

    public ExamQuestion currentQuestion() {
        if (complete()) {
            throw new IllegalStateException("Exam is already complete");
        }
        return questions.get(current);
    }

    public int questionNumber() {
        return current + 1;
    }

    public int totalQuestions() {
        return questions.size();
    }

    public int score() {
        return score;
    }

    public boolean passed() {
        return complete() && score >= definition.passingScore();
    }

    public boolean complete() {
        return current >= questions.size();
    }

    public ExamAnswer answer(char option) {
        if (complete()) {
            throw new IllegalStateException("Exam is already complete");
        }
        char normalized = Character.toUpperCase(option);
        ExamQuestion question = currentQuestion();
        if (!question.options().containsKey(normalized)) {
            return new ExamAnswer(ExamAnswer.Status.INVALID_OPTION, current, questions.size(), score);
        }
        if (question.correctOption() == normalized) {
            score++;
        }
        current++;
        ExamAnswer.Status status = complete()
                ? ExamAnswer.Status.COMPLETE : ExamAnswer.Status.NEXT_QUESTION;
        return new ExamAnswer(status, current, questions.size(), score);
    }
}
