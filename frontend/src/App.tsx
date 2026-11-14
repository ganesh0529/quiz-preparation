import { useCallback, useState } from 'react';
import type { RoomView, ServerMessage, Session } from './types';
import { loadSession, saveSession } from './session';
import { useRoomSocket } from './useRoomSocket';
import Home from './pages/Home';
import Lobby from './pages/Lobby';
import Game from './pages/Game';
import Podium from './pages/Podium';

/**
 * The screen shown is derived from the room phase pushed by the server:
 * LOBBY -> Lobby, QUESTION_OPEN / QUESTION_RESULTS -> Game, FINISHED -> Podium.
 */
export default function App() {
  const [session, setSessionState] = useState<Session | null>(loadSession);
  const [room, setRoom] = useState<RoomView | null>(null);
  // Local-clock deadline of the open question (server sends "remainingMs", which avoids clock skew).
  const [deadline, setDeadline] = useState(0);

  const setSession = useCallback((s: Session | null) => {
    saveSession(s);
    setSessionState(s);
    setRoom(null);
  }, []);

  const onMessage = useCallback((m: ServerMessage) => {
    switch (m.type) {
      case 'ROOM_STATE':
      case 'ROOM_UPDATE':
        setRoom(m.payload);
        if (m.payload.currentQuestion) setDeadline(Date.now() + m.payload.currentQuestion.remainingMs);
        break;
      case 'QUESTION_OPEN':
        setDeadline(Date.now() + m.payload.remainingMs);
        setRoom((r) => r && { ...r, phase: 'QUESTION_OPEN', currentQuestion: m.payload, answeredCount: 0, lastResults: null });
        break;
      case 'ANSWER_PROGRESS':
        setRoom((r) => r && { ...r, answeredCount: m.payload.answered });
        break;
      case 'QUESTION_RESULTS': {
        const scores = new Map(m.payload.leaderboard.map((e) => [e.playerId, e.score]));
        setRoom((r) =>
          r && {
            ...r,
            phase: 'QUESTION_RESULTS',
            lastResults: m.payload,
            leaderboard: m.payload.leaderboard,
            players: r.players.map((p) => ({ ...p, score: scores.get(p.id) ?? p.score })),
          },
        );
        break;
      }
      case 'GAME_ENDED':
        setRoom((r) => r && { ...r, phase: 'FINISHED', leaderboard: m.payload.leaderboard });
        break;
    }
  }, []);

  const { connected, unknownRoom } = useRoomSocket(session?.code, onMessage);
  const leave = () => setSession(null);

  if (!session) return <Home onSession={setSession} />;

  if (unknownRoom) {
    return (
      <div className="center card">
        <h2>Room {session.code} no longer exists</h2>
        <button onClick={leave}>Back to home</button>
      </div>
    );
  }

  if (!room) return <div className="center muted">Connecting to room {session.code}…</div>;

  return (
    <div className="app">
      <header className="topbar">
        <span className="logo">⚡ QuizClash</span>
        <span className="muted">
          Room <b>{room.code}</b> · {session.role === 'host' ? 'Host' : session.nickname}
          {!connected && <span className="warn"> · reconnecting…</span>}
        </span>
        <button className="link" onClick={leave}>Leave</button>
      </header>

      {room.phase === 'LOBBY' && <Lobby room={room} session={session} />}
      {(room.phase === 'QUESTION_OPEN' || room.phase === 'QUESTION_RESULTS') && (
        <Game room={room} session={session} deadline={deadline} />
      )}
      {room.phase === 'FINISHED' && <Podium room={room} session={session} onLeave={leave} />}
    </div>
  );
}
