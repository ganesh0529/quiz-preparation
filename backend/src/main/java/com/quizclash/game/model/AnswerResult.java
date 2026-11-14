package com.quizclash.game.model;

/** The scored outcome of one player's answer to one question. */
public record AnswerResult(
        String playerId,
        int answerIndex,
        boolean correct,
        int points,
        boolean usedHint,
        long elapsedMs) {}
