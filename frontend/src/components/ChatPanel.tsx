import { useEffect, useRef, useState, type FormEvent } from 'react';
import { streamSse } from '../api';

interface Msg {
  role: 'user' | 'bot';
  text: string;
}

interface Props {
  roomCode: string;
  playerId?: string;
  title: string;
  /** Sent automatically once when the panel mounts (lobby greeting). */
  autoGreet?: string;
  suggestions?: string[];
}

/**
 * Reusable chat with the AI host. Replies stream in token by token over SSE.
 * Conversation memory lives on the server (keyed by room + player), so the
 * same conversation continues from the lobby into the game.
 */
export default function ChatPanel({ roomCode, playerId, title, autoGreet, suggestions = [] }: Props) {
  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const listRef = useRef<HTMLDivElement>(null);
  const greeted = useRef(false);

  const send = async (text: string, showUser = true) => {
    if (!text.trim() || busy) return;
    setBusy(true);
    setInput('');
    setMessages((m) => [...m, ...(showUser ? [{ role: 'user' as const, text }] : []), { role: 'bot', text: '' }]);

    const appendToBot = (t: string) =>
      setMessages((m) => {
        const copy = [...m];
        const last = copy[copy.length - 1];
        copy[copy.length - 1] = { ...last, text: last.text + t };
        return copy;
      });

    try {
      await streamSse('/api/bot/chat', { roomCode, playerId, message: text }, appendToBot);
    } catch (e) {
      appendToBot(`⚠️ ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  useEffect(() => {
    if (autoGreet && !greeted.current) {
      greeted.current = true; // guard against React StrictMode double-invoking effects
      send(autoGreet, false);
    }
  }, []); // run once on mount

  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight });
  }, [messages]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    send(input);
  };

  return (
    <div className="card chat">
      <h3>🤖 {title}</h3>
      <div className="chat-list" ref={listRef}>
        {messages.length === 0 && <p className="muted small">Say hi to Quizzy, the AI host.</p>}
        {messages.map((m, i) => (
          <div key={i} className={`bubble ${m.role}`}>
            {m.text || <span className="typing">…</span>}
          </div>
        ))}
      </div>
      {suggestions.length > 0 && (
        <div className="chips">
          {suggestions.map((s) => (
            <button key={s} className="chip-btn" disabled={busy} onClick={() => send(s)}>
              {s}
            </button>
          ))}
        </div>
      )}
      <form className="chat-input" onSubmit={submit}>
        <input value={input} maxLength={500} placeholder="Message Quizzy…" onChange={(e) => setInput(e.target.value)} />
        <button disabled={busy || !input.trim()}>Send</button>
      </form>
    </div>
  );
}
