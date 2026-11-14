package com.quizclash.bot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizclash.bot.BotService.GeneratedQuestion;
import com.quizclash.game.GameException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
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
    private final DocumentTextExtractor extractor;
    private final ObjectMapper json;

    public BotController(BotService bot, DocumentTextExtractor extractor, ObjectMapper json) {
        this.bot = bot;
        this.extractor = extractor;
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

    /**
     * Generate questions from an uploaded document (multipart/form-data).
     * Fields: file (required), count 1-10, difficulty, focus (optional, e.g. "chapter 3").
     */
    @PostMapping(value = "/questions/from-document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<GeneratedQuestion> generateFromDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "5") int count,
            @RequestParam(defaultValue = "medium") String difficulty,
            @RequestParam(required = false) String focus) {
        if (count < 1 || count > 10) throw new GameException(HttpStatus.BAD_REQUEST, "count must be 1 to 10");
        if (focus != null && focus.length() > 200) throw new GameException(HttpStatus.BAD_REQUEST, "focus is too long");
        var excerpts = extractor.extract(file);
        return bot.generateFromDocument(excerpts, count, difficulty.isBlank() ? "medium" : difficulty, focus);
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
