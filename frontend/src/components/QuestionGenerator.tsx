import { useState } from 'react';
import { api } from '../api';
import type { GeneratedQuestion, RoomView } from '../types';

/**
 * Host-only: ask the AI for questions on a topic (Spring AI structured output),
 * review them, then add them to the game.
 */
export default function QuestionGenerator({ room, hostToken }: { room: RoomView; hostToken: string }) {
  const [topic, setTopic] = useState('');
  const [count, setCount] = useState(3);
  const [difficulty, setDifficulty] = useState('medium');
  const [draft, setDraft] = useState<GeneratedQuestion[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const generate = async () => {
    setBusy(true);
    setError('');
    try {
      setDraft(await api.generateQuestions(topic, count, difficulty));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const add = async () => {
    setBusy(true);
    setError('');
    try {
      await api.addQuestions(room.code, hostToken, draft);
      setDraft([]);
      setTopic('');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="card">
      <h3>✨ Generate questions with AI</h3>
      <div className="row wrap">
        <input placeholder="Topic, e.g. Space, 90s movies, Java" value={topic} maxLength={100} onChange={(e) => setTopic(e.target.value)} />
        <select value={count} onChange={(e) => setCount(Number(e.target.value))}>
          {[1, 2, 3, 4, 5].map((n) => (
            <option key={n} value={n}>{n}</option>
          ))}
        </select>
        <select value={difficulty} onChange={(e) => setDifficulty(e.target.value)}>
          <option>easy</option>
          <option>medium</option>
          <option>hard</option>
        </select>
        <button className="secondary" disabled={busy || !topic.trim()} onClick={generate}>
          {busy ? 'Thinking…' : 'Generate'}
        </button>
      </div>

      {draft.length > 0 && (
        <>
          <ol className="draft">
            {draft.map((q, i) => (
              <li key={i}>
                <b>{q.question}</b>
                <ul>
                  {q.options.map((o, j) => (
                    <li key={j} className={j === q.correctIndex ? 'good' : 'muted'}>
                      {j === q.correctIndex ? '✔ ' : ''}
                      {o}
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ol>
          <div className="row">
            <button disabled={busy} onClick={add}>Add {draft.length} to game</button>
            <button className="link" onClick={() => setDraft([])}>Discard</button>
          </div>
        </>
      )}
      {error && <p className="error">{error}</p>}
    </div>
  );
}
