import { useState } from 'react';
import { api } from '../api';
import type { RoomView, Session } from '../types';
import ChatPanel from '../components/ChatPanel';
import QuestionGenerator from '../components/QuestionGenerator';

export default function Lobby({ room, session }: { room: RoomView; session: Session }) {
  const [error, setError] = useState('');
  const isHost = session.role === 'host';
  const joinUrl = `${location.origin}/?code=${room.code}`;

  const start = async () => {
    setError('');
    try {
      await api.start(room.code, session.hostToken!);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <div className="layout">
      <main className="main">
        <div className="card center-text">
          <p className="muted">Join at <b>{location.host}</b> with code</p>
          <div className="room-code">{room.code}</div>
          <p className="muted small">{joinUrl}</p>
        </div>

        <div className="card">
          <h3>Players ({room.players.length})</h3>
          {room.players.length === 0 && <p className="muted">Waiting for players to join…</p>}
          <div className="chips">
            {room.players.map((p) => (
              <span key={p.id} className={`chip ${p.id === session.playerId ? 'me' : ''}`}>{p.nickname}</span>
            ))}
          </div>
        </div>

        {isHost ? (
          <>
            <div className="card row">
              <span className="muted">
                {room.questionCount} questions ready
                {room.players.length === 0 && (
                  <>
                    <br />
                    <span className="small">Start is enabled once at least one player joins. Open the link above in another window (incognito) to join.</span>
                  </>
                )}
              </span>
              <button onClick={start} disabled={room.players.length === 0}>Start game ▶</button>
            </div>
            {error && <p className="error">{error}</p>}
            <QuestionGenerator room={room} hostToken={session.hostToken!} />
          </>
        ) : (
          <div className="card center-text muted">You're in! Waiting for the host to start…</div>
        )}
      </main>

      <aside className="side">
        <ChatPanel
          roomCode={room.code}
          playerId={session.playerId}
          title="Quizzy · AI host"
          autoGreet={isHost ? undefined : `Hi, I'm ${session.nickname} and I just joined!`}
          suggestions={['How do I score points?', 'Who is in the room?', 'Hype me up!']}
        />
      </aside>
    </div>
  );
}
