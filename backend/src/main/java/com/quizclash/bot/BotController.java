package com.quizclash.bot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizclash.bot.BotService.GeneratedQuestion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Chatbot endpoints. Chat and hint stream over Server-Sent Events:
 * <pre>
 * event: token
 * data: {"t":"Hello"}
 *
 * event: done
 * data: {}
 * </pre>
 * Each token is wrapped in JSON so newlines inside the text survive SSE framing.
 */
@RestController
@RequestMapping("/api/bot")
public class BotController {

    public record ChatRequest(@NotBlank String roomCode, String playerId, @NotBlank @Size(max = 500) String message) {}
    public record HintRequest(@NotBlank String roomCode, @NotBlank String playerId) {}
    public record GenerateRequest(@NotBlank @Size(max = 100) String topic,
                                  @Min(1) @Max(10) Integer count,
                                  String difficulty) {}

    private final BotService bot;
    private final ObjectMapper json;

    public BotController(BotService bot, ObjectMapper json) {
        this.bot = bot;
        this.json = json;
    }

    /** Lobby host + live game questions (tool calling). Streams the reply. */
    @PostMapping("/chat")
    public Flux<ServerSentEvent<String>> chat(@Valid @RequestBody ChatRequest req) {
        return toSse(bot.chat(req.roomCode(), req.playerId(), req.message()));
    }

    /** Hint for the current question; the player's answer will be worth half. */
    @PostMapping("/hint")
    public Flux<ServerSentEvent<String>> hint(@Valid @RequestBody HintRequest req) {
        return toSse(bot.hint(req.roomCode(), req.playerId()));
    }

    /** Generate questions on a topic; the host reviews them and adds them via POST /api/rooms/{code}/questions. */
    @PostMapping("/questions")
    public List<GeneratedQuestion> generate(@Valid @RequestBody GenerateRequest req) {
        int count = req.count() == null ? 5 : req.count();
        String difficulty = req.difficulty() == null || req.difficulty().isBlank() ? "medium" : req.difficulty();
        return bot.generateQuestions(req.topic(), count, difficulty);
    }

    private Flux<ServerSentEvent<String>> toSse(Flux<String> tokens) {
        return tokens
                .map(t -> ServerSentEvent.<String>builder().event("token").data(asJson(t)).build())
                .concatWithValues(ServerSentEvent.<String>builder().event("done").data("{}").build());
    }

    private String asJson(String token) {
        try {
            return json.writeValueAsString(Map.of("t", token));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
