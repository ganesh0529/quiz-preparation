package com.quizclash.bot;

import com.quizclash.game.GameException;
import com.quizclash.game.RoomService;
import com.quizclash.game.model.Question;
import com.quizclash.game.view.Views.RoomView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The four chatbot features, built on Spring AI's {@link ChatClient}:
 * <ol>
 *   <li>Lobby host / live chat - {@link #chat}: streamed, with memory, with tools.</li>
 *   <li>Hints for points - {@link #hint}: streamed, costs half the points.</li>
 *   <li>Question generation - {@link #generateQuestions} (topic) and
 *       {@link #generateFromDocument} (uploaded file): structured JSON output.</li>
 *   <li>Live game questions - the {@link GameTools} attached to {@link #chat}.</li>
 * </ol>
 */
@Service
public class BotService {

    private static final Logger log = LoggerFactory.getLogger(BotService.class);
    static final String OFFLINE = "🤖 Quizzy is offline right now (no AI key configured). The game still works!";

    /** {@code source} is only filled for questions made from an uploaded document. */
    public record GeneratedQuestion(String question, List<String> options, int correctIndex, String source) {}
    /** Wrapper object: models produce a JSON object more reliably than a bare array. */
    public record GeneratedQuestions(List<GeneratedQuestion> questions) {}

    private final ChatClient chatClient;   // memory + tools, for conversation
    private final ChatClient plainClient;  // stateless, for hints and generation
    private final RoomService rooms;
    private final GameTools tools;
    private final boolean enabled;

    public BotService(ChatModel chatModel,
                      RoomService rooms,
                      GameTools tools,
                      @Value("${spring.ai.openai.api-key:}") String apiKey) {
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(20).build();
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();
        this.plainClient = ChatClient.create(chatModel);
        this.rooms = rooms;
        this.tools = tools;
        this.enabled = apiKey != null && !apiKey.isBlank() && !"not-set".equals(apiKey);
        if (!enabled) log.warn("OPENAI_API_KEY not set: chatbot runs in offline mode");
    }

    /** Features 1 + 4: the AI host, which can call game tools, streamed token by token. */
    public Flux<String> chat(String roomCode, String playerId, String message) {
        RoomView room = rooms.view(roomCode); // 404 if the room does not exist
        if (!enabled) return Flux.just(OFFLINE);

        String speaker = rooms.nickname(roomCode, playerId)
                .map(n -> "player \"" + n + "\"")
                .orElse("the game host (the person running the game)");
        String who = playerId == null || playerId.isBlank() ? "host" : playerId;

        return chatClient.prompt()
                .system(BotPrompts.chatSystem(room.code(), room.phase(), speaker))
                .user(message)
                .tools(tools)
                // Trusted values for the tools; never taken from the model's output.
                .toolContext(Map.of("roomCode", room.code(), "playerId", who))
                // One memory per (room, person) so conversations don't mix.
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, room.code() + ":" + who))
                .stream()
                .content()
                .onErrorResume(e -> {
                    log.warn("chat failed: {}", e.getMessage());
                    return Flux.just("🤖 Oops, I lost my train of thought. Try again?");
                });
    }

    /** Feature 2: a hint for the current question. Marks the hint as used (half points). */
    public Flux<String> hint(String roomCode, String playerId) {
        if (!enabled) return Flux.just(OFFLINE); // check first so an offline bot doesn't cost points
        Question q = rooms.useHint(roomCode, playerId);
        return plainClient.prompt()
                .system(BotPrompts.HINT_SYSTEM)
                .user(BotPrompts.hintUser(q))
                .stream()
                .content()
                .onErrorResume(e -> {
                    log.warn("hint failed: {}", e.getMessage());
                    return Flux.just("🤖 No hint available, sorry. Trust your gut!");
                });
    }

    /** Feature 3: new questions as structured output matching { question, options[4], correctIndex }. */
    public List<GeneratedQuestion> generateQuestions(String topic, int count, String difficulty) {
        if (!enabled) throw new GameException(HttpStatus.SERVICE_UNAVAILABLE, "AI is not configured (OPENAI_API_KEY)");
        GeneratedQuestions out = plainClient.prompt()
                .system(BotPrompts.GENERATE_SYSTEM)
                .user(BotPrompts.generateUser(topic, count, difficulty))
                .call()
                .entity(GeneratedQuestions.class);   // Spring AI adds the JSON schema and parses the reply
        return clean(out, count, false);
    }

    /**
     * Feature 3b: questions from an uploaded document. Same structured output,
     * but the model may only ask about what the excerpts say, and each question
     * carries the passage that proves its answer.
     */
    public List<GeneratedQuestion> generateFromDocument(DocumentTextExtractor.Excerpts doc,
                                                        int count, String difficulty, String focus) {
        if (!enabled) throw new GameException(HttpStatus.SERVICE_UNAVAILABLE, "AI is not configured (OPENAI_API_KEY)");
        log.info("Generating {} questions from {} ({} chars, {} excerpt(s))",
                count, doc.fileName(), doc.totalChars(), doc.parts().size());
        GeneratedQuestions out = plainClient.prompt()
                .system(BotPrompts.DOCUMENT_SYSTEM)
                .user(BotPrompts.documentUser(doc.fileName(), doc.parts(), count, difficulty, focus))
                .call()
                .entity(GeneratedQuestions.class);
        return clean(out, count, true);
    }

    private static List<GeneratedQuestion> clean(GeneratedQuestions out, int count, boolean keepSource) {
        if (out == null || out.questions() == null) return List.of();
        return out.questions().stream()
                .filter(BotService::isValid)
                .map(q -> new GeneratedQuestion(q.question(), q.options(), q.correctIndex(),
                        keepSource ? trimSource(q.source()) : null))
                .map(BotService::shuffleOptions)
                .limit(count)
                .toList();
    }

    private static String trimSource(String source) {
        if (source == null || source.isBlank()) return null;
        String s = source.strip();
        return s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }

    private static boolean isValid(GeneratedQuestion q) {
        return q != null && q.question() != null && !q.question().isBlank()
                && q.options() != null && q.options().size() == 4
                && q.options().stream().allMatch(o -> o != null && !o.isBlank())
                && q.correctIndex() >= 0 && q.correctIndex() <= 3;
    }

    /** LLMs love putting the right answer first; shuffle so it isn't predictable. */
    private static GeneratedQuestion shuffleOptions(GeneratedQuestion q) {
        String correct = q.options().get(q.correctIndex());
        List<String> options = new ArrayList<>(q.options());
        Collections.shuffle(options);
        return new GeneratedQuestion(q.question(), options, options.indexOf(correct), q.source());
    }
}
