package com.quizclash.game;

import org.springframework.http.HttpStatus;

/** A rule of the game was broken (unknown room, wrong phase, ...). Mapped to an HTTP status. */
public class GameException extends RuntimeException {

    private final HttpStatus status;

    public GameException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static GameException notFound(String what) {
        return new GameException(HttpStatus.NOT_FOUND, what + " not found");
    }

    public static GameException conflict(String message) {
        return new GameException(HttpStatus.CONFLICT, message);
    }
}
