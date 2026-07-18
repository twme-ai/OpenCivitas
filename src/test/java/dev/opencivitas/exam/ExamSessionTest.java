package dev.opencivitas.exam;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExamSessionTest {
    private static final ExamQuestion FIRST = question("First", 'A');
    private static final ExamQuestion SECOND = question("Second", 'B');

    @Test
    void invalidOptionDoesNotAdvanceTheExam() {
        ExamSession session = session(false, 1);

        ExamAnswer result = session.answer('Z');

        assertEquals(ExamAnswer.Status.INVALID_OPTION, result.status());
        assertEquals(1, session.questionNumber());
        assertEquals(0, session.score());
    }

    @Test
    void scoresAnswersAndCompletesAtTheLastQuestion() {
        ExamSession session = session(false, 2);

        assertEquals(ExamAnswer.Status.NEXT_QUESTION, session.answer('A').status());
        assertEquals(ExamAnswer.Status.COMPLETE, session.answer('B').status());
        assertTrue(session.complete());
        assertTrue(session.passed());
        assertEquals(2, session.score());
        assertThrows(IllegalStateException.class, session::currentQuestion);
    }

    @Test
    void failedScoreDoesNotPass() {
        ExamSession session = session(false, 2);
        session.answer('C');
        session.answer('B');

        assertTrue(session.complete());
        assertFalse(session.passed());
        assertEquals(1, session.score());
    }

    @Test
    void randomizationNeverMutatesConfiguredQuestions() {
        List<ExamQuestion> configured = List.of(FIRST, SECOND);
        ExamDefinition definition = definition(true, 1, configured);

        new ExamSession(definition, new Random(42));

        assertEquals(List.of(FIRST, SECOND), definition.questions());
    }

    private static ExamSession session(boolean randomize, int passingScore) {
        return new ExamSession(definition(randomize, passingScore, List.of(FIRST, SECOND)), new Random(7));
    }

    private static ExamDefinition definition(
            boolean randomize,
            int passingScore,
            List<ExamQuestion> questions
    ) {
        LocalizedText title = text("Title");
        return new ExamDefinition("test", "test", passingScore, randomize, title, title, questions);
    }

    private static ExamQuestion question(String prompt, char correct) {
        return new ExamQuestion(text(prompt), Map.of(
                'A', text("A"),
                'B', text("B"),
                'C', text("C")
        ), correct);
    }

    private static LocalizedText text(String value) {
        return new LocalizedText(Map.of("en_US", value));
    }
}
