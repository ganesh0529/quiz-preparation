package com.quizclash.game.kafka;

import java.util.Map;

/** Value of a record on the {@code game-events} topic. Key = roomCode. */
public record GameEvent(Type type, String roomCode, long timestamp, Map<String, Object> data) {

    public enum Type { GAME_CREATED, PLAYER_JOINED, GAME_STARTED, QUESTION_OPEN, QUESTION_CLOSED, GAME_ENDED }

    public static GameEvent of(Type type, String roomCode, Map<String, Object> data) {
        return new GameEvent(type, roomCode, System.currentTimeMillis(), data);
    }
}
