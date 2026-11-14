package com.quizclash.game.model;

/** Lifecycle of a room. */
public enum Phase {
    /** Waiting for players; host can add questions. */
    LOBBY,
    /** A question is on screen and answers are accepted. */
    QUESTION_OPEN,
    /** The question is closed; results + leaderboard are shown. */
    QUESTION_RESULTS,
    /** Game over, podium. */
    FINISHED
}
