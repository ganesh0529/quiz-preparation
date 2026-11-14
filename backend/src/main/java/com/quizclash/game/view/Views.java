package com.quizclash.game.view;

import com.quizclash.game.model.Phase;

import java.util.List;

/**
 * JSON shapes sent to clients (REST responses and WebSocket payloads).
 * Kept separate from the domain model so the correct answer is never
 * leaked by accident while a question is open.
 */
public final class Views {

    private Views() {}

    public record PlayerView(String id, String nickname, int score) {}

    /** A question as players see it: no correctIndex. */
    public record QuestionView(
            String id,
            int index,
            int total,
            String question,
            List<String> options,
            long timeLimitMs,
            long remainingMs) {}

    public record LeaderboardEntry(int rank, String playerId, String nickname, int score, int correctCount) {}

    public record PlayerResult(
            String playerId,
            String nickname,
            Integer answerIndex,   // null = did not answer
            boolean correct,
            int points,
            boolean usedHint) {}

    public record QuestionResults(
            String questionId,
            int index,
            int correctIndex,
            int[] distribution,
            List<PlayerResult> results,
            List<LeaderboardEntry> leaderboard) {}

    public record AnswerProgress(String questionId, int answered, int total) {}

    public record RoomView(
            String code,
            Phase phase,
            List<PlayerView> players,
            int questionCount,
            QuestionView currentQuestion,      // null unless a question is open or showing results
            int answeredCount,
            QuestionResults lastResults,       // null unless phase == QUESTION_RESULTS
            List<LeaderboardEntry> leaderboard) {}
}
