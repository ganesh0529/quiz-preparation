package com.quizclash.game;

import org.springframework.stereotype.Component;

/**
 * Pure scoring rules, Kahoot style.
 *
 * <ul>
 *   <li>Wrong or missing answer: 0 points.</li>
 *   <li>Correct answer: from 1000 (instant) down to 500 (at the buzzer), linear in time.</li>
 *   <li>Hint used: the answer is worth half.</li>
 * </ul>
 */
@Component
public class ScoringService {

    public static final int MAX_POINTS = 1000;

    public int score(boolean correct, long elapsedMs, long timeLimitMs, boolean usedHint) {
        if (!correct) return 0;
        double ratio = Math.clamp((double) elapsedMs / timeLimitMs, 0.0, 1.0);
        int points = (int) Math.round(MAX_POINTS * (1.0 - ratio / 2.0));
        return usedHint ? points / 2 : points;
    }
}
