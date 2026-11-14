package com.quizclash.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics. Spring Kafka's KafkaAdmin creates them on startup
 * if they do not exist yet (auto topic creation is disabled on the broker).
 */
@Configuration
public class KafkaTopicsConfig {

    @Bean
    NewTopic quizAnswersTopic(QuizClashProperties props) {
        // Several partitions so the scoring consumer group can scale out.
        // The record key is the room code, so one room always maps to one
        // partition and its answers are processed strictly in order.
        return TopicBuilder.name(props.topics().answers())
                .partitions(props.topics().partitions())
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic gameEventsTopic(QuizClashProperties props) {
        return TopicBuilder.name(props.topics().gameEvents())
                .partitions(props.topics().partitions())
                .replicas(1)
                .build();
    }
}
