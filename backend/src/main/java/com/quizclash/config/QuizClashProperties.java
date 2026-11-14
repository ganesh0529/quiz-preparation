package com.quizclash.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed view of the {@code quizclash.*} block in application.yml. */
@ConfigurationProperties(prefix = "quizclash")
public record QuizClashProperties(
        int questionTimeSeconds,
        int questionsPerGame,
        long closeGraceMs,
        Topics topics) {

    public record Topics(String answers, String gameEvents, int partitions) {}

    public long questionTimeMs() {
        return questionTimeSeconds * 1000L;
    }
}
