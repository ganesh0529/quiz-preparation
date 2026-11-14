package com.quizclash.game.kafka;

/**
 * Value of a record on the {@code quiz-answers} topic. Key = roomCode.
 *
 * @param eventId          unique id, lets the consumer spot duplicates on redelivery
 * @param clientTimestamp  when the browser says the player clicked (informational only)
 * @param serverTimestamp  when the API received the answer: used for the speed
 *                         bonus, because a client clock can be wrong or faked
 */
public record AnswerEvent(
        String eventId,
        String roomCode,
        String playerId,
        String questionId,
        int answerIndex,
        Long clientTimestamp,
        long serverTimestamp) {}
