package com.quizclash.game;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringServiceTest {

    private final ScoringService scoring = new ScoringService();
    private static final long LIMIT = 20_000;

    @Test
    void wrongAnswerScoresZero() {
        assertThat(scoring.score(false, 100, LIMIT, false)).isZero();
    }

    @Test
    void instantCorrectAnswerScoresMax() {
        assertThat(scoring.score(true, 0, LIMIT, false)).isEqualTo(1000);
    }

    @Test
    void answerAtTheBuzzerScoresHalf() {
        assertThat(scoring.score(true, LIMIT, LIMIT, false)).isEqualTo(500);
    }

    @Test
    void fasterBeatsSlower() {
        assertThat(scoring.score(true, 2_000, LIMIT, false))
                .isGreaterThan(scoring.score(true, 15_000, LIMIT, false));
    }

    @Test
    void hintHalvesThePoints() {
        assertThat(scoring.score(true, 0, LIMIT, true)).isEqualTo(500);
    }

    @Test
    void elapsedIsClampedToTheWindow() {
        assertThat(scoring.score(true, -50, LIMIT, false)).isEqualTo(1000);
        assertThat(scoring.score(true, 99_000, LIMIT, false)).isEqualTo(500);
    }
}
