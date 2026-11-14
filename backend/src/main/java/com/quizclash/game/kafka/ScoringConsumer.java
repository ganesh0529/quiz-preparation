package com.quizclash.game.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizclash.game.RoomService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The scoring consumer group.
 *
 * <p>Reads every answer from {@code quiz-answers}, scores it (correctness +
 * speed bonus, halved if a hint was used), updates the room's leaderboard
 * and pushes progress to the room over WebSocket. {@code concurrency = 3}
 * gives one listener thread per partition; because records are keyed by room
 * code, each room is only ever handled by one thread, in order.
 */
@Component
public class ScoringConsumer {

    private static final Logger log = LoggerFactory.getLogger(ScoringConsumer.class);

    private final RoomService rooms;
    private final ObjectMapper json;

    public ScoringConsumer(RoomService rooms, ObjectMapper json) {
        this.rooms = rooms;
        this.json = json;
    }

    @KafkaListener(
            topics = "${quizclash.topics.answers}",
            groupId = "scoring",
            concurrency = "${quizclash.topics.partitions}")
    public void onAnswer(ConsumerRecord<String, String> record) {
        try {
            AnswerEvent event = json.readValue(record.value(), AnswerEvent.class);
            RoomService.ScoreOutcome outcome = rooms.scoreAnswer(event);
            log.info("room={} partition={} offset={} player={} -> {}",
                    record.key(), record.partition(), record.offset(), event.playerId(), outcome);
        } catch (Exception e) {
            // A bad record must not block the partition forever: log and move on.
            // (A production system would send it to a dead-letter topic.)
            log.error("Skipping unprocessable answer at partition {} offset {}",
                    record.partition(), record.offset(), e);
        }
    }
}
