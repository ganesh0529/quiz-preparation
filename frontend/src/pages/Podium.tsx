import type { RoomView, Session } from '../types';
import Leaderboard from '../components/Leaderboard';
import ChatPanel from '../components/ChatPanel';

export default function Podium({ room, session, onLeave }: { room: RoomView; session: Session; onLeave: () => void }) {
  const [first, second, third] = room.leaderboard;
  const me = room.leaderboard.find((e) => e.playerId === session.playerId);

  return (
    <div className="layout">
      <main className="main">
        <h1 className="center-text">🏆 Final results</h1>

        <div className="podium">
          {[second, first, third].map((e, i) =>
            e ? (
              <div key={e.playerId} className={`step step-${['2', '1', '3'][i]}`}>
                <div className="medal">{['🥈', '🥇', '🥉'][i]}</div>
                <div className="name">{e.nickname}</div>
                <div className="pts">{e.score} pts</div>
              </div>
            ) : (
              <div key={i} className="step empty" />
            ),
          )}
        </div>

        {me && (
          <p className="center-text big">
            You finished <b>#{me.rank}</b> with <b>{me.score}</b> points
          </p>
        )}

        <div className="card">
          <h3>All players</h3>
          <Leaderboard entries={room.leaderboard} highlightId={session.playerId} />
        </div>

        <button onClick={onLeave}>Play again</button>
      </main>
      <aside className="side">
        <ChatPanel roomCode={room.code} playerId={session.playerId} title="Quizzy" suggestions={['Who won?', 'How did I do?']} />
      </aside>
    </div>
  );
}
