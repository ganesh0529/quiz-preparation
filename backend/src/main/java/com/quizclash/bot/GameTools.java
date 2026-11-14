package com.quizclash.bot;

import com.quizclash.game.RoomService;
import com.quizclash.game.view.Views.LeaderboardEntry;
import com.quizclash.game.view.Views.RoomView;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Tools the LLM may call while chatting ("who's winning?", "what's my rank?").
 *
 * <p>The room code and player id come from the {@link ToolContext}, which the
 * server fills in, not from the model. So a player cannot talk the bot into
 * reading another room, and none of these tools expose the correct answer.
 */
@Component
public class GameTools {

    public record MyRank(String nickname, Integer rank, int score, int totalPlayers, String note) {}

    public record RoomStatus(String phase, int players, List<String> nicknames,
                             Integer currentQuestionNumber, int totalQuestions,
                             Long secondsRemaining, int answeredSoFar) {}

    private final RoomService rooms;

    public GameTools(RoomService rooms) {
        this.rooms = rooms;
    }

    @Tool(description = "Get the current leaderboard of the user's room: rank, nickname, score and number of "
            + "correct answers for every player. Scores only include questions that are already closed.")
    public List<LeaderboardEntry> getLeaderboard(ToolContext ctx) {
        return rooms.leaderboard(roomCode(ctx));
    }

    @Tool(description = "Get the rank and score of the player who is chatting with you.")
    public MyRank getMyRank(ToolContext ctx) {
        String playerId = (String) ctx.getContext().getOrDefault("playerId", "");
        List<LeaderboardEntry> board = rooms.leaderboard(roomCode(ctx));
        return board.stream()
                .filter(e -> e.playerId().equals(playerId))
                .findFirst()
                .map(e -> new MyRank(e.nickname(), e.rank(), e.score(), board.size(), null))
                .orElse(new MyRank(null, null, 0, board.size(), "The user is the host, not a player."));
    }

    @Tool(description = "Get the status of the user's room: phase (LOBBY, QUESTION_OPEN, QUESTION_RESULTS, "
            + "FINISHED), players, which question number is on screen, seconds remaining and how many "
            + "players have answered. Never contains the question text or the answer.")
    public RoomStatus getRoomStatus(ToolContext ctx) {
        RoomView v = rooms.view(roomCode(ctx));
        var q = v.currentQuestion();
        return new RoomStatus(
                v.phase().name(),
                v.players().size(),
                v.players().stream().map(p -> p.nickname()).toList(),
                q == null ? null : q.index() + 1,
                v.questionCount(),
                q == null ? null : Math.round(q.remainingMs() / 1000.0),
                v.answeredCount());
    }

    private static String roomCode(ToolContext ctx) {
        return (String) ctx.getContext().get("roomCode");
    }
}
