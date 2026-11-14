import { useState } from 'react';
import { api, streamSse } from '../api';
import type { QuestionView, RoomView, Session } from '../types';
import ChatPanel from '../components/ChatPanel';
import Countdown from '../components/Countdown';
import Leaderboard from '../components/Leaderboard';

const SHAPES = ['▲', '◆', '●', '■'];

export default function Game({ room, session, deadline }: { room: RoomView; session: Session; deadline: number }) {
  const q = room.currentQuestion!;
  return (
    <div className="layout">
      <main className="main">
        {/* key = question id: all per-question state resets when a new question opens */}
        <QuestionBoard key={q.id} room={room} q={q} session={session} deadline={deadline} />
      </main>
      <aside className="side">
        <div className="card">
          <h3>Leaderboard</h3>
          <Leaderboard entries={room.leaderboard} highlightId={session.playerId} limit={5} />
        </div>
        <ChatPanel
          roomCode={room.code}
          playerId={session.playerId}
          title="Ask Quizzy"
          suggestions={["Who's winning?", "What's my rank?", 'How many answered?']}
        />
      </aside>
    </div>
  );
}

function QuestionBoard({ room, q, session, deadline }: { room: RoomView; q: QuestionView; session: Session; deadline: number }) {
  const isHost = session.role === 'host';
  const open = room.phase === 'QUESTION_OPEN';
  const results = room.lastResults?.questionId === q.id ? room.lastResults : null;

  const [myAnswer, setMyAnswer] = useState<number | null>(null);
  const [hint, setHint] = useState('');
  const [hintState, setHintState] = useState<'none' | 'loading' | 'done'>('none');
  const [error, setError] = useState('');

  const answer = async (index: number) => {
    if (!open || myAnswer !== null || isHost) return;
    setMyAnswer(index); // optimistic: lock the buttons immediately
    try {
      await api.answer(room.code, session.playerId!, q.id, index);
    } catch (e) {
      setMyAnswer(null);
      setError((e as Error).message);
    }
  };

  const askHint = async () => {
    setHintState('loading');
    setError('');
    try {
      await streamSse('/api/bot/hint', { roomCode: room.code, playerId: session.playerId }, (t) => setHint((h) => h + t));
      setHintState('done');
    } catch (e) {
      setHintState('none');
      setError((e as Error).message);
    }
  };

  const next = async () => {
    try {
      await api.next(room.code, session.hostToken!);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const mine = results?.results.find((r) => r.playerId === session.playerId);
  const isLast = q.index + 1 >= q.total;

  return (
    <>
      <div className="card question-head">
        <span className="muted">Question {q.index + 1} / {q.total}</span>
        {open ? <Countdown deadline={deadline} totalMs={q.timeLimitMs} /> : <span className="pill">Time's up</span>}
      </div>

      <div className="card question-text">{q.question}</div>

      <div className="answers">
        {q.options.map((opt, i) => {
          const isCorrect = results && i === results.correctIndex;
          const classes = [
            'answer',
            `a${i}`,
            myAnswer === i ? 'picked' : '',
            results && !isCorrect ? 'dim' : '',
            isCorrect ? 'correct' : '',
          ].join(' ');
          return (
            <button key={i} className={classes} disabled={!open || myAnswer !== null || isHost} onClick={() => answer(i)}>
              <span className="shape">{SHAPES[i]}</span>
              <span className="opt">{opt}</span>
              {results && <span className="count">{results.distribution[i]}</span>}
            </button>
          );
        })}
      </div>

      {/* Player status line */}
      {!isHost && open && myAnswer !== null && <div className="card center-text">Answer locked in! 🔒 Waiting for others…</div>}
      {!isHost && results && (
        <div className={`card center-text big ${mine?.correct ? 'good' : 'bad'}`}>
          {mine?.answerIndex == null ? '⏰ No answer' : mine.correct ? `✅ Correct! +${mine.points}` : '❌ Wrong answer'}
          {mine?.usedHint && <span className="muted small"> (hint used: half points)</span>}
        </div>
      )}

      {/* Hint */}
      {!isHost && open && myAnswer === null && hintState === 'none' && (
        <button className="secondary" onClick={askHint}>💡 Ask Quizzy for a hint (half points)</button>
      )}
      {hint && <div className="card hint">💡 {hint}</div>}

      {/* Host controls */}
      {isHost && (
        <div className="card row">
          <span className="muted">
            {room.answeredCount} / {room.players.length} answered
          </span>
          <button onClick={next}>{open ? 'Show results' : isLast ? 'Finish game 🏁' : 'Next question ▶'}</button>
        </div>
      )}

      {error && <p className="error">{error}</p>}
    </>
  );
}
