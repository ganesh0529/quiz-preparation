package com.quizclash.game.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

/**
 * In-memory state of one game room.
 *
 * <p>Not thread-safe on its own: HTTP threads, the Kafka scoring consumer and
 * the question timer all touch it, so every read-modify-write happens inside
 * {@code synchronized (room)} in {@link com.quizclash.game.RoomService}.
 */
public class Room {

    private final String code;
    private final String hostToken;
    private final Map<String, Player> players = new LinkedHashMap<>();
    private final List<Question> questions;

    private Phase phase = Phase.LOBBY;
    private int currentIndex = -1;
    private long questionOpenedAt;
    private long questionDeadline;
    private ScheduledFuture<?> closeTask;
    private long lastActivity = System.currentTimeMillis();

    /** questionId -> (playerId -> result) */
    private final Map<String, Map<String, AnswerResult>> answers = new HashMap<>();
    /** "questionId:playerId" for every hint that was used */
    private final Set<String> hintsUsed = new HashSet<>();

    public Room(String code, String hostToken, List<Question> questions) {
        this.code = code;
        this.hostToken = hostToken;
        this.questions = new ArrayList<>(questions);
    }

    // ---- players ----
    public void addPlayer(Player p) { players.put(p.id(), p); touch(); }
    public Optional<Player> player(String id) { return Optional.ofNullable(players.get(id)); }
    public Collection<Player> players() { return players.values(); }

    // ---- questions ----
    public List<Question> questions() { return questions; }
    public void addQuestions(List<Question> extra) { questions.addAll(extra); touch(); }

    public Optional<Question> currentQuestion() {
        return currentIndex >= 0 && currentIndex < questions.size()
                ? Optional.of(questions.get(currentIndex))
                : Optional.empty();
    }

    public boolean hasNextQuestion() { return currentIndex + 1 < questions.size(); }

    // ---- answers & hints ----
    public Map<String, AnswerResult> answersFor(String questionId) {
        return answers.computeIfAbsent(questionId, k -> new LinkedHashMap<>());
    }

    public boolean hasAnswered(String questionId, String playerId) {
        return answersFor(questionId).containsKey(playerId);
    }

    public boolean markHintUsed(String questionId, String playerId) {
        return hintsUsed.add(questionId + ":" + playerId);
    }

    public boolean usedHint(String questionId, String playerId) {
        return hintsUsed.contains(questionId + ":" + playerId);
    }

    // ---- simple accessors ----
    public String code() { return code; }
    public String hostToken() { return hostToken; }
    public Phase phase() { return phase; }
    public void phase(Phase phase) { this.phase = phase; touch(); }
    public int currentIndex() { return currentIndex; }
    public void currentIndex(int i) { this.currentIndex = i; }
    public long questionOpenedAt() { return questionOpenedAt; }
    public long questionDeadline() { return questionDeadline; }

    public void openWindow(long openedAt, long deadline) {
        this.questionOpenedAt = openedAt;
        this.questionDeadline = deadline;
    }

    public ScheduledFuture<?> closeTask() { return closeTask; }
    public void closeTask(ScheduledFuture<?> f) { this.closeTask = f; }

    public long lastActivity() { return lastActivity; }
    public void touch() { lastActivity = System.currentTimeMillis(); }
}
