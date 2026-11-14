package com.quizclash.game.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * A second, independent consumer group on {@code game-events}.
 * It only logs, but it shows the pub/sub side of Kafka: any number of
 * groups (analytics, achievements, replays...) can read the same events
 * without the game knowing about them.
 */
@Component
public class GameEventAuditConsumer {

    private static final Logger log = LoggerFactory.getLogger(GameEventAuditConsumer.class);

    @KafkaListener(topics = "${quizclash.topics.game-events}", groupId = "audit")
    public void onEvent(ConsumerRecord<String, String> record) {
        log.info("[audit] room={} {}", record.key(), record.value());
    }
}
