# How QuizClash works: a guided tour

This walks through **one complete game** and points at the code that runs at each step.
Read it with the code open beside you.

---

## 0. Startup

`docker compose up` starts three containers:

1. **kafka**: one node in KRaft mode (it is its own controller, no ZooKeeper).
   Auto topic creation is off on purpose.
2. **backend**: on startup, Spring Kafka's `KafkaAdmin` sees the two `NewTopic` beans in
   `config/KafkaTopicsConfig.java` and creates `quiz-answers` and `game-events`
   (3 partitions each). The listeners in `ScoringConsumer` and `GameEventAuditConsumer`
   join their consumer groups (`scoring`, `audit`) and get partitions assigned.
   `QuestionBank` loads `resources/questions.json`.
3. **frontend**: nginx serves the built React app and forwards `/api/*` and `/ws/*` to the
   backend (`frontend/nginx.conf`), so the browser only ever talks to one origin.

---

## 1. Host creates a room

```
Browser ──POST /api/rooms──► RoomController.create() ──► RoomService.createRoom()
```

`RoomService.createRoom()`:
- picks 8 random seed questions,
- generates a 5-character code from an alphabet without look-alikes (no `0/O`, `1/I/L`),
- stores the `Room` in a `ConcurrentHashMap`,
- returns `{ code, hostToken }`. The token is a random UUID that proves "I am the host"
  for start/next/add-questions. No login needed.
- publishes `GAME_CREATED` to `game-events`.

The frontend saves `{ code, role: 'host', hostToken }` in **sessionStorage**
(`session.ts`). sessionStorage is per tab, which is why you can test host and player in
two tabs of the same browser.

## 2. Everyone opens the room's WebSocket

`App.tsx` calls `useRoomSocket(code)` which opens `ws://host/ws/rooms/ABCDE`.

On the server, `RoomSocketHandler.afterConnectionEstablished`:
- registers the session in `RoomBroadcaster` (a map `roomCode → sessions`), wrapped in a
  `ConcurrentWebSocketSessionDecorator` so several threads can send safely;
- immediately sends a **`ROOM_STATE`** snapshot. That is how a page refresh recovers: the
  client never has to replay history, it just gets the current state.

The socket is **push-only**. Clients send commands over REST. That keeps validation and
errors in normal HTTP land.

## 3. Players join

```
POST /api/rooms/ABCDE/join { nickname } ──► RoomService.join()
```

Checks: room is in `LOBBY`, not full, nickname not taken. Then `ROOM_UPDATE` is broadcast
and every lobby screen shows the new player chip.

**Lobby chat (AI feature 1).** `Lobby.tsx` renders `<ChatPanel autoGreet="Hi, I'm …">`,
which sends a first message to `POST /api/bot/chat`. In `BotService.chat()`:

```java
chatClient.prompt()
    .system(BotPrompts.chatSystem(room.code(), room.phase(), speaker))  // persona + rules + phase
    .user(message)
    .tools(tools)                                                       // @Tool methods
    .toolContext(Map.of("roomCode", ..., "playerId", ...))               // trusted values
    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, room + ":" + player))
    .stream().content();                                                 // Flux<String>
```

- The **system prompt changes with the game phase**: in the lobby Quizzy hypes and explains
  rules; during a question it refuses to help except to point at the Hint button.
- `MessageChatMemoryAdvisor` keeps the last 20 messages per room + player, so the bot
  remembers you from lobby into the game.
- `BotController` turns the `Flux<String>` into Server-Sent Events. Each token is wrapped
  as `{"t":"..."}` so newlines inside the text survive SSE framing.
- In the browser, `streamSse()` in `api.ts` reads the response body and parses the SSE
  frames by hand, because `EventSource` only supports GET.

## 4. Host adds AI-generated questions (AI feature 3)

`QuestionGenerator.tsx` → `POST /api/bot/questions { topic, count, difficulty }`.

```java
plainClient.prompt()
    .system(BotPrompts.GENERATE_SYSTEM)
    .user("Write 3 medium questions about: Space")
    .call()
    .entity(GeneratedQuestions.class);
```

`.entity(...)` is Spring AI's **structured output**: it appends a JSON schema derived from
the Java record to the prompt and parses the reply into the record. The service then
validates every question (exactly 4 options, index 0..3) and **shuffles the options**,
because LLMs tend to put the right answer first. The host reviews and clicks
"Add to game" → `POST /api/rooms/{code}/questions`.

## 5. Host starts the game

`POST /start` → `RoomService.start()` → `openQuestion(room, 0)`:

- `phase = QUESTION_OPEN`, stores `openedAt` and `deadline = openedAt + 20s`;
- schedules `closeIfStillOpen` at `deadline + 1s grace` on the `taskScheduler`;
- broadcasts **`QUESTION_OPEN`** with a `QuestionView`: **no `correctIndex`**. The view
  records in `game/view/Views.java` exist so the answer can't leak by accident.

The payload carries `remainingMs` instead of an absolute time. The client computes
`deadline = Date.now() + remainingMs`, so a wrong clock on a player's laptop doesn't matter.

## 6. A player answers: the Kafka part

```
Game.tsx ──POST /api/rooms/ABCDE/answers──► RoomController.answer()
```

**Step 1, validate cheaply** (`RoomService.prepareAnswer`): question open, right
question id, not answered yet, before the deadline. Then build an `AnswerEvent` with
`serverTimestamp = now`.

**Step 2, publish** (`AnswerProducer.publish`):

```java
kafka.send("quiz-answers", event.roomCode(), json).get(3, SECONDS);
```

- **Key = room code.** Kafka hashes the key to choose the partition, so a room's answers
  always land on the same partition, in order.
- `acks=all` + idempotent producer + waiting for the result means the 202 response is only
  sent once Kafka has stored the event. Waiting is cheap because Spring Boot runs requests
  on Java 21 virtual threads (`spring.threads.virtual.enabled`).
- The endpoint returns **202 Accepted**. It has not scored anything.

The UI locks the buttons optimistically ("Answer locked in! 🔒").

**Step 3, consume and score** (`ScoringConsumer.onAnswer` → `RoomService.scoreAnswer`):

```java
@KafkaListener(topics = "${quizclash.topics.answers}", groupId = "scoring", concurrency = "3")
```

`concurrency = 3` = one thread per partition. Because of the keying, one room is only ever
handled by one thread, so answers for a room are scored in arrival order.

Inside `scoreAnswer` (under the room lock):
1. unknown room/player → ignore;
2. not the current question, or `serverTimestamp > deadline` → `LATE`;
3. already answered → `DUPLICATE` (Kafka delivers *at least once*, and double clicks
   happen, so the consumer must be idempotent);
4. `points = ScoringService.score(correct, elapsed, 20s, usedHint)`;
5. store the result **but don't add it to the total yet**;
6. broadcast `ANSWER_PROGRESS` ("3 / 5 answered");
7. if everyone has answered → close the question now instead of waiting for the timer.

Watch it live: `docker compose logs -f backend` shows lines like
`room=ABCDE partition=1 offset=17 player=… -> SCORED`. With
`docker compose --profile tools up`, Kafka UI on :8081 shows the messages per partition.

## 7. Hints (AI feature 2)

"💡 Ask Quizzy for a hint" → `POST /api/bot/hint`.

`RoomService.useHint()` marks `questionId:playerId` as used (only once, only before
answering). The hint prompt includes the question, the options and the correct option
"for reference only", with hard rules not to reveal it. When the answer is scored later,
`usedHint` is true and `ScoringService` halves the points.

## 8. Question closes, leaderboard is pushed

Either the timer fires (`closeIfStillOpen`), the last player answers, or the host clicks
"Show results". All three call `closeQuestion(room)`:

- cancels the timer;
- adds each stored result to the players' totals (**this is the moment scores change**);
- `phase = QUESTION_RESULTS`;
- broadcasts **`QUESTION_RESULTS`**: correct index, answer distribution, each player's
  points, and the new leaderboard;
- publishes `QUESTION_CLOSED` to `game-events`.

Why the 1 second grace? An answer accepted at 19.9 s still has to travel through Kafka.
Closing exactly at 20.0 s could drop it.

## 9. "Who's winning?" (AI feature 4: tool calling)

During the game the chat panel offers "Who's winning?". The model sees the tool
descriptions from `GameTools`:

```java
@Tool(description = "Get the current leaderboard of the user's room ...")
public List<LeaderboardEntry> getLeaderboard(ToolContext ctx) { ... }
```

Flow: the model replies "call `getLeaderboard`" → Spring AI runs the Java method → sends
the JSON result back to the model → the model writes "Priya leads with 2,450 points 🔥"
→ streamed to the browser.

The tools take **no arguments from the model**. Room code and player id come from
`ToolContext`, which the server filled in. A player can't make the bot look at another room,
and no tool returns question text or answers.

## 10. Next question … and the podium

Host clicks "Next question ▶" → `RoomService.next()` → `openQuestion(index + 1)`. After the
last question the button says "Finish game 🏁" → `finish(room)` → `phase = FINISHED` →
`GAME_ENDED` with the final leaderboard → `Podium.tsx`.

Rooms nobody has touched for 2 hours are removed by `evictIdleRooms()` (`@Scheduled`).

---

## Concurrency in one paragraph

Three kinds of threads touch a room: HTTP requests (virtual threads), Kafka listener
threads, and the timer thread. Every read-modify-write on a `Room` happens inside
`synchronized (room)`. Different rooms never block each other. Kafka's per-room ordering
means the scoring thread for a room never competes with another scoring thread for the
same room.

## Things to try

- Change `quizclash.question-time-seconds` in `application.yml`.
- Scale scoring: set `quizclash.topics.partitions: 6` (Spring's KafkaAdmin adds the partitions on restart) and watch the listener
  log which partitions each thread owns.
- Add a new consumer group on `game-events`, e.g. a "fastest finger" achievements service.
- Swap OpenAI for a local model with the Ollama starter.
