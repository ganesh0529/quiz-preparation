import type { LeaderboardEntry } from '../types';

export default function Leaderboard({
  entries,
  highlightId,
  limit,
}: {
  entries: LeaderboardEntry[];
  highlightId?: string;
  limit?: number;
}) {
  if (entries.length === 0) return <p className="muted">No players yet.</p>;
  const shown = limit ? entries.slice(0, limit) : entries;
  const me = entries.find((e) => e.playerId === highlightId);

  return (
    <ol className="leaderboard">
      {shown.map((e) => (
        <li key={e.playerId} className={e.playerId === highlightId ? 'me' : ''}>
          <span className="rank">{e.rank}</span>
          <span className="name">{e.nickname}</span>
          <span className="score">{e.score}</span>
        </li>
      ))}
      {/* Always show the current player, even outside the top N */}
      {me && limit && !shown.includes(me) && (
        <li className="me">
          <span className="rank">{me.rank}</span>
          <span className="name">{me.nickname}</span>
          <span className="score">{me.score}</span>
        </li>
      )}
    </ol>
  );
}
