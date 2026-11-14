import { useEffect, useState } from 'react';

/** Seconds left + a shrinking bar, based on a local-clock deadline. */
export default function Countdown({ deadline, totalMs }: { deadline: number; totalMs: number }) {
  const [now, setNow] = useState(Date.now());

  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 100);
    return () => window.clearInterval(id);
  }, []);

  const left = Math.max(0, deadline - now);
  const pct = Math.min(100, (left / totalMs) * 100);

  return (
    <div className="countdown">
      <span className={`secs ${left < 5000 ? 'urgent' : ''}`}>{Math.ceil(left / 1000)}</span>
      <div className="bar">
        <div className="fill" style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}
