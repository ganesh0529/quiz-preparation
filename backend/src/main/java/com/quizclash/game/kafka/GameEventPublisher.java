package com.quizclash.game.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizclash.config.QuizClashProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Publishes room lifecycle events to {@code game-events}. Fire-and-forget:
 * the game never waits on this topic, it is an audit/analytics stream.
 */
@Component
public class GameEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(GameEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final String topic;

    public GameEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json, QuizClashProperties props) {
        this.kafka = kafka;
        this.json = json;
        this.topic = props.topics().gameEvents();
    }

    public void publish(GameEvent.Type type, String roomCode, Map<String, Object> data) {
        try {
            String value = json.writeValueAsString(GameEvent.of(type, roomCode, data));
            kafka.send(topic, roomCode, value).whenComplete((r, ex) -> {
                if (ex != null) log.warn("game event {} for {} not published: {}", type, roomCode, ex.getMessage());
            });
        } catch (JsonProcessingException e) {
            log.warn("could not serialize game event", e);
        }
    }
}
