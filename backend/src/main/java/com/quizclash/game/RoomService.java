package com.quizclash.game;

import com.quizclash.config.QuizClashProperties;
import com.quizclash.config.SchedulingConfig;
import com.quizclash.game.kafka.AnswerEvent;
import com.quizclash.game.kafka.GameEvent;
import com.quizclash.game.kafka.GameEventPublisher;
import com.quizclash.game.model.AnswerResult;
import com.quizclash.game.model.Phase;
import com.quizclash.game.model.Player;
import com.quizclash.game.model.Question;
import com.quizclash.game.model.Room;
import com.quizclash.game.view.Views.AnswerProgress;
import com.quizclash.game.view.Views.LeaderboardEntry;
import com.quizclash.game.view.Views.PlayerResult;
import com.quizclash.game.view.Views.PlayerView;
import com.quizclash.game.view.Views.QuestionResults;
import com.quizclash.game.view.Views.QuestionView;
import com.quizclash.game.view.Views.RoomView;
import com.quizclash.game.ws.RoomBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns all rooms and the game lifecycle:
 * LOBBY -> QUESTION_OPEN -> QUESTION_RESULTS -> ... -> FINISHED.
 *
 * <p>State is in memory (v1 has no database). Every mutation of a room happens
 * inside {@code synchronized (room)} because three kinds of threads touch it:
 * HTTP requests, the Kafka scoring consumer and the question timer.
 */
@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; // no 0/O, 1/I/L
    private static final int CODE_LENGTH = 5;
    private static final int MAX_PLAYERS = 50;
    private static final int MAX_QUESTIONS = 30;
    private static final Duration ROOM_TTL = Duration.ofHours(2);

    public enum ScoreOutcome { SCORED, UNKNOWN_ROOM, UNKNOWN_PLAYER, NOT_CURRENT_QUESTION, LATE, DUPLICATE }

    public record CreatedRoom(String code, String hostToken) {}
    public record JoinedPlayer(String playerId, String nickname, String code) {}

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private final QuestionBank questionBank;
    private final ScoringService scoring;
    private final RoomBroadcaster broadcaster;
    private final GameEventPublisher events;
    private final TaskScheduler scheduler;
    private final QuizClashProperties props;

    public RoomService(QuestionBank questionBank,
                       ScoringService scoring,
                       RoomBroadcaster broadcaster,
                       GameEventPublisher events,
                       @Qualifier(SchedulingConfig.SCHEDULER) TaskScheduler scheduler,
                       QuizClashProperties props) {
        this.questionBank = questionBank;
        this.scoring = scoring;
        this.broadcaster = broadcaster;
        this.events = events;
        this.scheduler = scheduler;
        this.props = props;
    }

    // =====================================================================
    // Lifecycle commands (called from REST)
    // =====================================================================

    public CreatedRoom createRoom() {
        String hostToken = UUID.randomUUID().toString();
        List<Question> questions = questionBank.randomSet(props.questionsPerGame());
        Room room;
        do {
            room = new Room(randomCode(), hostToken, questions);
        } while (rooms.putIfAbsent(room.code(), room) != null);

        events.publish(GameEvent.Type.GAME_CREATED, room.code(), Map.of("questions", questions.size()));
        log.info("Room {} created", room.code());
        return new CreatedRoom(room.code(), hostToken);
    }

    public JoinedPlayer join(String code, String rawNickname) {
        String nickname = rawNickname == null ? "" : rawNickname.trim();
        if (nickname.isEmpty() || nickname.length() > 20) {
            throw new GameException(HttpStatus.BAD_REQUEST, "Nickname must be 1-20 characters");
        }
        Room room = require(code);
        Player player;
        RoomView view;
        synchronized (room) {
            if (room.phase() != Phase.LOBBY) throw GameException.conflict("Game already started");
            if (room.players().size() >= MAX_PLAYERS) throw GameException.conflict("Room is full");
            boolean taken = room.players().stream().anyMatch(p -> p.nickname().equalsIgnoreCase(nickname));
            if (taken) throw GameException.conflict("Nickname already taken in this room");

            player = new Player(UUID.randomUUID().toString(), nickname);
            room.addPlayer(player);
            view = viewOf(room);
        }
        broadcaster.send(room.code(), "ROOM_UPDATE", view);
        events.publish(GameEvent.Type.PLAYER_JOINED, room.code(), Map.of("nickname", nickname));
        return new JoinedPlayer(player.id(), player.nickname(), room.code());
    }

    public void addQuestions(String code, String hostToken, List<Question> extra) {
        Room room = requireHost(code, hostToken);
        RoomView view;
        synchronized (room) {
            if (room.phase() != Phase.LOBBY) throw GameException.conflict("Questions can only be added in the lobby");
            if (room.questions().size() + extra.size() > MAX_QUESTIONS) {
                throw GameException.conflict("A game can have at most " + MAX_QUESTIONS + " questions");
            }
            room.addQuestions(extra);
            view = viewOf(room);
        }
        broadcaster.send(room.code(), "ROOM_UPDATE", view);
    }

    public void start(String code, String hostToken) {
        Room room = requireHost(code, hostToken);
        synchronized (room) {
            if (room.phase() != Phase.LOBBY) throw GameException.conflict("Game already started");
            if (room.players().isEmpty()) throw GameException.conflict("Wait for at least one player to join");
            if (room.questions().isEmpty()) throw GameException.conflict("This game has no questions");

            events.publish(GameEvent.Type.GAME_STARTED, room.code(), Map.of("players", room.players().size()));
            openQuestion(room, 0);
        }
    }

    /**
     * The host's "next" button.
     * While a question is open it closes it early (show results);
     * while results are shown it opens the next question, or ends the game.
     */
    public void next(String code, String hostToken) {
        Room room = requireHost(code, hostToken);
        synchronized (room) {
            switch (room.phase()) {
                case QUESTION_OPEN -> closeQuestion(room);
                case QUESTION_RESULTS -> {
                    if (room.hasNextQuestion()) openQuestion(room, room.currentIndex() + 1);
                    else finish(room);
                }
                default -> throw GameException.conflict("Nothing to advance in phase " + room.phase());
            }
        }
    }

    // =====================================================================
    // Answers: validated on the HTTP thread, scored by the Kafka consumer
    // =====================================================================

    /**
     * Cheap checks done before publishing to Kafka so the player gets an
     * immediate error for obviously invalid answers. Returns the event to publish.
     */
    public AnswerEvent prepareAnswer(String code, String playerId, String questionId, int answerIndex, Long clientTs) {
        if (answerIndex < 0 || answerIndex > 3) throw new GameException(HttpStatus.BAD_REQUEST, "answerIndex must be 0..3");
        Room room = require(code);
        long now = System.currentTimeMillis();
        synchronized (room) {
            room.player(playerId).orElseThrow(() -> GameException.notFound("Player"));
            if (room.phase() != Phase.QUESTION_OPEN) throw GameException.conflict("Question is closed");
            Question q = room.currentQuestion().orElseThrow();
            if (!q.id().equals(questionId)) throw GameException.conflict("Not the current question");
            if (room.hasAnswered(questionId, playerId)) throw GameException.conflict("Already answered");
            if (now > room.questionDeadline()) throw GameException.conflict("Time is up");
        }
        return new AnswerEvent(UUID.randomUUID().toString(), room.code(), playerId, questionId, answerIndex, clientTs, now);
    }

    /** Called by {@link com.quizclash.game.kafka.ScoringConsumer} for every record on quiz-answers. */
    public ScoreOutcome scoreAnswer(AnswerEvent e) {
        Room room = rooms.get(e.roomCode());
        if (room == null) return ScoreOutcome.UNKNOWN_ROOM;

        synchronized (room) {
            Optional<Player> player = room.player(e.playerId());
            if (player.isEmpty()) return ScoreOutcome.UNKNOWN_PLAYER;

            Question q = room.currentQuestion().orElse(null);
            if (q == null || !q.id().equals(e.questionId())) return ScoreOutcome.NOT_CURRENT_QUESTION;
            if (room.phase() != Phase.QUESTION_OPEN || e.serverTimestamp() > room.questionDeadline()) {
                return ScoreOutcome.LATE;
            }
            // Idempotency: Kafka is at-least-once, and a double click can publish twice.
            if (room.hasAnswered(q.id(), e.playerId())) return ScoreOutcome.DUPLICATE;

            long elapsed = e.serverTimestamp() - room.questionOpenedAt();
            boolean correct = e.answerIndex() == q.correctIndex();
            boolean usedHint = room.usedHint(q.id(), e.playerId());
            int points = scoring.score(correct, elapsed, props.questionTimeMs(), usedHint);

            // Points are stored now but only added to the totals when the question
            // closes, so nobody (or the AI bot) can see mid-question who got it right.
            room.answersFor(q.id()).put(e.playerId(),
                    new AnswerResult(e.playerId(), e.answerIndex(), correct, points, usedHint, elapsed));

            int answered = room.answersFor(q.id()).size();
            int total = room.players().size();
            broadcaster.send(room.code(), "ANSWER_PROGRESS", new AnswerProgress(q.id(), answered, total));

            if (answered >= total) closeQuestion(room); // everyone answered: no need to wait for the timer
            return ScoreOutcome.SCORED;
        }
    }

    /** Marks a hint as used for the current question and returns that question (for the bot). */
    public Question useHint(String code, String playerId) {
        Room room = require(code);
        synchronized (room) {
            room.player(playerId).orElseThrow(() -> GameException.notFound("Player"));
            if (room.phase() != Phase.QUESTION_OPEN) throw GameException.conflict("Hints are only available while a question is open");
            Question q = room.currentQuestion().orElseThrow();
            if (room.hasAnswered(q.id(), playerId)) throw GameException.conflict("You already answered this question");
            if (!room.markHintUsed(q.id(), playerId)) throw GameException.conflict("You already used your hint for this question");
            return q;
        }
    }

    // =====================================================================
    // Queries
    // =====================================================================

    public RoomView view(String code) {
        Room room = require(code);
        synchronized (room) {
            return viewOf(room);
        }
    }

    public List<LeaderboardEntry> leaderboard(String code) {
        Room room = require(code);
        synchronized (room) {
            return leaderboardOf(room);
        }
    }

    public Optional<String> nickname(String code, String playerId) {
        Room room = rooms.get(normalize(code));
        if (room == null || playerId == null) return Optional.empty();
        synchronized (room) {
            return room.player(playerId).map(Player::nickname);
        }
    }

    // =====================================================================
    // Internals (callers must hold the room lock)
    // =====================================================================

    private void openQuestion(Room room, int index) {
        long now = System.currentTimeMillis();
        long deadline = now + props.questionTimeMs();
        room.currentIndex(index);
        room.openWindow(now, deadline);
        room.phase(Phase.QUESTION_OPEN);

        // Auto-close shortly after the deadline. The grace period lets answers
        // that were accepted just before the buzzer finish their trip through Kafka.
        String code = room.code();
        room.closeTask(scheduler.schedule(
                () -> closeIfStillOpen(code, index),
                Instant.ofEpochMilli(deadline + props.closeGraceMs())));

        broadcaster.send(code, "QUESTION_OPEN", questionViewOf(room));
        events.publish(GameEvent.Type.QUESTION_OPEN, code, Map.of("index", index));
    }

    private void closeIfStillOpen(String code, int index) {
        Room room = rooms.get(code);
        if (room == null) return;
        synchronized (room) {
            if (room.phase() == Phase.QUESTION_OPEN && room.currentIndex() == index) closeQuestion(room);
        }
    }

    private void closeQuestion(Room room) {
        if (room.closeTask() != null) room.closeTask().cancel(false);
        Question q = room.currentQuestion().orElseThrow();

        // Apply this question's points to the running totals.
        for (AnswerResult r : room.answersFor(q.id()).values()) {
            room.player(r.playerId()).ifPresent(p -> p.addPoints(r.points(), r.correct()));
        }
        room.phase(Phase.QUESTION_RESULTS);

        broadcaster.send(room.code(), "QUESTION_RESULTS", resultsOf(room));
        events.publish(GameEvent.Type.QUESTION_CLOSED, room.code(),
                Map.of("index", room.currentIndex(), "answers", room.answersFor(q.id()).size()));
    }

    private void finish(Room room) {
        room.phase(Phase.FINISHED);
        List<LeaderboardEntry> board = leaderboardOf(room);
        broadcaster.send(room.code(), "GAME_ENDED", Map.of("leaderboard", board));
        events.publish(GameEvent.Type.GAME_ENDED, room.code(),
                Map.of("winner", board.isEmpty() ? "" : board.getFirst().nickname()));
    }

    private RoomView viewOf(Room room) {
        boolean showingQuestion = room.phase() == Phase.QUESTION_OPEN || room.phase() == Phase.QUESTION_RESULTS;
        String qid = room.currentQuestion().map(Question::id).orElse(null);
        return new RoomView(
                room.code(),
                room.phase(),
                room.players().stream().map(p -> new PlayerView(p.id(), p.nickname(), p.score())).toList(),
                room.questions().size(),
                showingQuestion ? questionViewOf(room) : null,
                qid == null ? 0 : room.answersFor(qid).size(),
                room.phase() == Phase.QUESTION_RESULTS ? resultsOf(room) : null,
                leaderboardOf(room));
    }

    private QuestionView questionViewOf(Room room) {
        Question q = room.currentQuestion().orElseThrow();
        long remaining = room.phase() == Phase.QUESTION_OPEN
                ? Math.max(0, room.questionDeadline() - System.currentTimeMillis())
                : 0;
        return new QuestionView(q.id(), room.currentIndex(), room.questions().size(),
                q.question(), q.options(), props.questionTimeMs(), remaining);
    }

    private QuestionResults resultsOf(Room room) {
        Question q = room.currentQuestion().orElseThrow();
        Map<String, AnswerResult> answers = room.answersFor(q.id());
        int[] distribution = new int[4];
        List<PlayerResult> results = new ArrayList<>();
        for (Player p : room.players()) {
            AnswerResult r = answers.get(p.id());
            if (r != null) distribution[r.answerIndex()]++;
            results.add(new PlayerResult(p.id(), p.nickname(),
                    r == null ? null : r.answerIndex(),
                    r != null && r.correct(),
                    r == null ? 0 : r.points(),
                    room.usedHint(q.id(), p.id())));
        }
        return new QuestionResults(q.id(), room.currentIndex(), q.correctIndex(), distribution, results, leaderboardOf(room));
    }

    private List<LeaderboardEntry> leaderboardOf(Room room) {
        List<Player> sorted = room.players().stream()
                .sorted(Comparator.comparingInt(Player::score).reversed()
                        .thenComparing(Comparator.comparingInt(Player::correctCount).reversed())
                        .thenComparing(Player::nickname, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<LeaderboardEntry> board = new ArrayList<>();
        int rank = 0;
        for (int i = 0; i < sorted.size(); i++) {
            Player p = sorted.get(i);
            if (i == 0 || p.score() != sorted.get(i - 1).score()) rank = i + 1; // ties share a rank
            board.add(new LeaderboardEntry(rank, p.id(), p.nickname(), p.score(), p.correctCount()));
        }
        return board;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private Room require(String code) {
        Room room = rooms.get(normalize(code));
        if (room == null) throw GameException.notFound("Room " + code);
        return room;
    }

    private Room requireHost(String code, String hostToken) {
        Room room = require(code);
        if (hostToken == null || !room.hostToken().equals(hostToken)) {
            throw new GameException(HttpStatus.FORBIDDEN, "Only the host can do that");
        }
        return room;
    }

    private static String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        return sb.toString();
    }

    /** Forget rooms nobody has touched for a while, so memory doesn't grow forever. */
    @Scheduled(fixedRate = 10 * 60 * 1000)
    void evictIdleRooms() {
        long cutoff = System.currentTimeMillis() - ROOM_TTL.toMillis();
        rooms.values().removeIf(room -> {
            synchronized (room) {
                if (room.lastActivity() >= cutoff) return false;
                if (room.closeTask() != null) room.closeTask().cancel(false);
                broadcaster.closeRoom(room.code());
                log.info("Room {} evicted after inactivity", room.code());
                return true;
            }
        });
    }
}
