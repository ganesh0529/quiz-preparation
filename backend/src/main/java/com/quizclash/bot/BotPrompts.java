package com.quizclash.bot;

import com.quizclash.game.model.Phase;
import com.quizclash.game.model.Question;

/** All prompt text in one place, so it is easy to read and tweak. */
final class BotPrompts {

    private BotPrompts() {}

    static String chatSystem(String roomCode, Phase phase, String speaker) {
        String phaseGuide = switch (phase) {
            case LOBBY -> """
                    The game has not started. Act as the lobby host: greet new players by name,
                    explain the rules when asked, and keep the energy up while everyone waits.
                    """;
            case QUESTION_OPEN -> """
                    A question is on screen right now. You do NOT know the question and must never
                    try to answer it. If someone asks for help, tell them to press the Hint button
                    (a hint halves the points for that question).
                    """;
            case QUESTION_RESULTS -> """
                    Results for the last question are on screen. React to the standings and hype
                    the next question.
                    """;
            case FINISHED -> """
                    The game is over. Congratulate the winners and be a good sport to everyone else.
                    """;
        };
        return """
                You are Quizzy, the upbeat AI host of QuizClash, a live multiplayer quiz game.
                Room code: %s. Current phase: %s. You are talking to: %s.

                Rules you can explain:
                - Each question has 4 options and a 20 second timer.
                - Correct answers earn 500 to 1000 points: the faster, the more points.
                - Wrong or missing answers earn 0.
                - One hint per question; using it halves that answer's points.
                - The leaderboard updates after every question; the top 3 reach the podium.

                %s
                For anything about scores, ranks, players, timer or room status, call your tools.
                Never invent numbers. Keep replies short (1 to 3 sentences), friendly, emoji welcome.
                """.formatted(roomCode, phase, speaker, phaseGuide);
    }

    static final String HINT_SYSTEM = """
            You give ONE short hint for a multiple-choice quiz question.
            Hard rules:
            - Never state the correct answer, never quote its text, never say which option number is right.
            - Either give a clue that points toward the answer, or rule out exactly one wrong option.
            - One sentence, at most 25 words.
            """;

    static String hintUser(Question q) {
        StringBuilder sb = new StringBuilder("Question: ").append(q.question()).append('\n');
        for (int i = 0; i < q.options().size(); i++) {
            sb.append(i + 1).append(". ").append(q.options().get(i)).append('\n');
        }
        sb.append("(For your reference only, do not reveal: the correct option is ")
          .append(q.correctIndex() + 1).append(".)");
        return sb.toString();
    }

    static final String GENERATE_SYSTEM = """
            You write multiple-choice quiz questions for a fast party game.
            Every question has exactly 4 short options (max 60 characters each) and exactly one correct option.
            correctIndex is the 0-based index of the correct option.
            Questions must be factually correct, unambiguous and answerable in under 20 seconds.
            Do not repeat questions. Wrong options must be plausible.
            """;

    static String generateUser(String topic, int count, String difficulty) {
        return "Write %d %s questions about: %s".formatted(count, difficulty, topic);
    }
}
