package com.quizclash.game.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizclash.config.QuizClashProperties;
import com.quizclash.game.GameException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Publishes answers to {@code quiz-answers}. This is the only thing the
 * answer endpoint does: it never scores synchronously.
 */
@Component
public class AnswerProducer {

    private static final Logger log = LoggerFactory.getLogger(AnswerProducer.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final String topic;

    public AnswerProducer(KafkaTemplate<String, String> kafka, ObjectMapper json, QuizClashProperties props) {
        this.kafka = kafka;
        this.json = json;
        this.topic = props.topics().answers();
    }

    public void publish(AnswerEvent event) {
        try {
            String value = json.writeValueAsString(event);
            // KEY = room code -> same partition for the whole room -> ordered processing.
            // We wait for the broker's ack (acks=all) so a 202 really means "stored".
            // Blocking is cheap here because requests run on virtual threads.
            var result = kafka.send(topic, event.roomCode(), value).get(3, TimeUnit.SECONDS);
            log.debug("answer {} -> partition {} offset {}", event.eventId(),
                    result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GameException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted while publishing answer");
        } catch (Exception e) {
            log.error("Could not publish answer to Kafka", e);
            throw new GameException(HttpStatus.SERVICE_UNAVAILABLE, "Answer could not be recorded, try again");
        }
    }
}
