import { useState, type FormEvent } from 'react';
import { api } from '../api';
import type { Session } from '../types';

export default function Home({ onSession }: { onSession: (s: Session) => void }) {
  const [code, setCode] = useState(() => new URLSearchParams(location.search).get('code')?.toUpperCase() ?? '');
  const [nickname, setNickname] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    setError('');
    try {
      await fn();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const create = () =>
    run(async () => {
      const { code, hostToken } = await api.createRoom();
      onSession({ code, role: 'host', hostToken });
    });

  const join = (e: FormEvent) => {
    e.preventDefault();
    run(async () => {
      const res = await api.join(code.trim(), nickname.trim());
      onSession({ code: res.code, role: 'player', playerId: res.playerId, nickname: res.nickname });
    });
  };

  return (
    <div className="home">
      <h1 className="hero">⚡ QuizClash</h1>
      <p className="muted">Live multiplayer quiz · React + Spring Boot + Kafka + Spring AI</p>

      <div className="home-grid">
        <form className="card" onSubmit={join}>
          <h2>Join a game</h2>
          <input
            placeholder="ROOM CODE"
            value={code}
            maxLength={6}
            onChange={(e) => setCode(e.target.value.toUpperCase())}
            className="code-input"
            autoFocus
          />
          <input placeholder="Nickname" value={nickname} maxLength={20} onChange={(e) => setNickname(e.target.value)} />
          <button disabled={busy || !code.trim() || !nickname.trim()}>Join</button>
        </form>

        <div className="card">
          <h2>Host a game</h2>
          <p className="muted">Create a room, share the code, and run the quiz from this screen.</p>
          <button className="secondary" disabled={busy} onClick={create}>Create room</button>
        </div>
      </div>

      {error && <p className="error">{error}</p>}
    </div>
  );
}
