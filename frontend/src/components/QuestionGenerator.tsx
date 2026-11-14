import { useRef, useState } from 'react';
import { api } from '../api';
import type { GeneratedQuestion, RoomView } from '../types';

const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
const ACCEPTED = '.pdf,.docx,.doc,.pptx,.ppt,.odt,.rtf,.txt,.md,.html';

type Mode = 'topic' | 'document';

/**
 * Host-only: ask the AI for questions (Spring AI structured output), either
 * on a topic or from an uploaded document, review them, then add them to the game.
 */
export default function QuestionGenerator({ room, hostToken }: { room: RoomView; hostToken: string }) {
  const [mode, setMode] = useState<Mode>('topic');
  const [topic, setTopic] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [focus, setFocus] = useState('');
  const [count, setCount] = useState(3);
  const [difficulty, setDifficulty] = useState('medium');
  const [draft, setDraft] = useState<GeneratedQuestion[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const fileInput = useRef<HTMLInputElement>(null);

  const switchMode = (m: Mode) => {
    setMode(m);
    setCount(3); // 3 is valid in both modes' count lists
    setDraft([]);
    setError('');
  };

  const pickFile = (f: File | null) => {
    setError('');
    if (f && f.size > MAX_UPLOAD_BYTES) {
      setError('That file is larger than 10 MB.');
      setFile(null);
      if (fileInput.current) fileInput.current.value = '';
      return;
    }
    setFile(f);
  };

  const generate = async () => {
    setBusy(true);
    setError('');
    try {
      setDraft(
        mode === 'topic'
          ? await api.generateQuestions(topic, count, difficulty)
          : await api.generateFromDocument(file!, count, difficulty, focus),
      );
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
      setFocus('');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const ready = mode === 'topic' ? topic.trim().length > 0 : file !== null;

  return (
    <div className="card">
      <h3>✨ Generate questions with AI</h3>

      <div className="tabs">
        <button className={mode === 'topic' ? 'tab active' : 'tab'} onClick={() => switchMode('topic')}>From a topic</button>
        <button className={mode === 'document' ? 'tab active' : 'tab'} onClick={() => switchMode('document')}>From a document</button>
      </div>

      {mode === 'topic' ? (
        <div className="row wrap">
          <input placeholder="Topic, e.g. Space, 90s movies, Java" value={topic} maxLength={100} onChange={(e) => setTopic(e.target.value)} />
        </div>
      ) : (
        <div className="stack">
          <label className="file-drop">
            <input
              ref={fileInput}
              type="file"
              accept={ACCEPTED}
              onChange={(e) => pickFile(e.target.files?.[0] ?? null)}
            />
            {file ? (
              <span>📄 <b>{file.name}</b> <span className="muted small">({(file.size / 1024 / 1024).toFixed(1)} MB)</span></span>
            ) : (
              <span className="muted">📄 Choose a PDF, Word, PowerPoint or text file (max 10 MB)</span>
            )}
          </label>
          <input placeholder="Optional focus, e.g. chapter 3, the causes of WW1" value={focus} maxLength={200} onChange={(e) => setFocus(e.target.value)} />
          <p className="muted small">The document's text is sent to the AI provider to write the questions.</p>
        </div>
      )}

      <div className="row wrap">
        <select value={count} onChange={(e) => setCount(Number(e.target.value))} aria-label="Number of questions">
          {(mode === 'topic' ? [1, 2, 3, 4, 5] : [3, 5, 8, 10]).map((n) => (
            <option key={n} value={n}>{n} questions</option>
          ))}
        </select>
        <select value={difficulty} onChange={(e) => setDifficulty(e.target.value)} aria-label="Difficulty">
          <option>easy</option>
          <option>medium</option>
          <option>hard</option>
        </select>
        <button className="secondary" disabled={busy || !ready} onClick={generate}>
          {busy ? (mode === 'document' ? 'Reading document…' : 'Thinking…') : 'Generate'}
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
                {q.source && <blockquote className="source">“{q.source}”</blockquote>}
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
