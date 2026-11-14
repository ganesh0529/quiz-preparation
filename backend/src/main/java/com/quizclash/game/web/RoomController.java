package com.quizclash.game.web;

import com.quizclash.game.RoomService;
import com.quizclash.game.RoomService.CreatedRoom;
import com.quizclash.game.RoomService.JoinedPlayer;
import com.quizclash.game.kafka.AnswerEvent;
import com.quizclash.game.kafka.AnswerProducer;
import com.quizclash.game.model.Question;
import com.quizclash.game.view.Views.LeaderboardEntry;
import com.quizclash.game.view.Views.RoomView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    // ---- request bodies ----
    public record JoinRequest(@NotBlank @Size(max = 20) String nickname) {}
    public record HostRequest(@NotBlank String hostToken) {}
    public record AnswerRequest(@NotBlank String playerId,
                                @NotBlank String questionId,
                                @Min(0) @Max(3) int answerIndex,
                                Long clientTimestamp) {}
    public record QuestionInput(@NotBlank String question,
                                @NotEmpty @Size(min = 4, max = 4) List<@NotBlank String> options,
                                @Min(0) @Max(3) int correctIndex,
                                @Size(max = 600) String source) {}
    public record AddQuestionsRequest(@NotBlank String hostToken,
                                      @NotEmpty List<@Valid QuestionInput> questions) {}

    private final RoomService rooms;
    private final AnswerProducer answerProducer;

    public RoomController(RoomService rooms, AnswerProducer answerProducer) {
        this.rooms = rooms;
        this.answerProducer = answerProducer;
    }

    /** Host creates a room and receives a secret hostToken for host-only actions. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedRoom create() {
        return rooms.createRoom();
    }

    @GetMapping("/{code}")
    public RoomView get(@PathVariable String code) {
        return rooms.view(code);
    }

    @PostMapping("/{code}/join")
    public JoinedPlayer join(@PathVariable String code, @Valid @RequestBody JoinRequest req) {
        return rooms.join(code, req.nickname());
    }

    @PostMapping("/{code}/questions")
    public RoomView addQuestions(@PathVariable String code, @Valid @RequestBody AddQuestionsRequest req) {
        List<Question> questions = req.questions().stream()
                .map(q -> new Question("gen-" + UUID.randomUUID().toString().substring(0, 8),
                        q.question(), q.options(), q.correctIndex(), q.source()))
                .toList();
        rooms.addQuestions(code, req.hostToken(), questions);
        return rooms.view(code);
    }

    @PostMapping("/{code}/start")
    public RoomView start(@PathVariable String code, @Valid @RequestBody HostRequest req) {
        rooms.start(code, req.hostToken());
        return rooms.view(code);
    }

    @PostMapping("/{code}/next-question")
    public RoomView next(@PathVariable String code, @Valid @RequestBody HostRequest req) {
        rooms.next(code, req.hostToken());
        return rooms.view(code);
    }

    /**
     * The Kafka producer side. Validates cheaply, publishes the answer to
     * {@code quiz-answers} and returns 202 Accepted. Scoring happens later,
     * asynchronously, in the scoring consumer.
     */
    @PostMapping("/{code}/answers")
    public ResponseEntity<Map<String, String>> answer(@PathVariable String code, @Valid @RequestBody AnswerRequest req) {
        AnswerEvent event = rooms.prepareAnswer(code, req.playerId(), req.questionId(), req.answerIndex(), req.clientTimestamp());
        answerProducer.publish(event);
        return ResponseEntity.accepted().body(Map.of("status", "ACCEPTED", "eventId", event.eventId()));
    }

    @GetMapping("/{code}/leaderboard")
    public List<LeaderboardEntry> leaderboard(@PathVariable String code) {
        return rooms.leaderboard(code);
    }
}
