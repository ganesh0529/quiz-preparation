package com.quizclash.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Explicit scheduler for question timers and room cleanup.
 *
 * <p>Why not rely on Boot's auto-configured one? {@code @EnableWebSocket}
 * registers its own (no-op) SockJS TaskScheduler bean, which makes Boot back
 * off. Naming ours "taskScheduler" makes it the one {@code @Scheduled} uses too.
 */
@Configuration
public class SchedulingConfig {

    public static final String SCHEDULER = "taskScheduler";

    @Bean(name = SCHEDULER)
    ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("game-timer-");
        scheduler.initialize();
        return scheduler;
    }
}
