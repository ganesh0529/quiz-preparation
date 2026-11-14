package com.quizclash.game.model;

/** A player in a room. Mutated only while holding the room's lock. */
public class Player {

    private final String id;
    private final String nickname;
    private int score;
    private int correctCount;

    public Player(String id, String nickname) {
        this.id = id;
        this.nickname = nickname;
    }

    public void addPoints(int points, boolean correct) {
        score += points;
        if (correct) correctCount++;
    }

    public String id() { return id; }
    public String nickname() { return nickname; }
    public int score() { return score; }
    public int correctCount() { return correctCount; }
}
