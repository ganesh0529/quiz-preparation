import type { Session } from './types';

// sessionStorage is per browser tab, so you can be the host in one tab
// and a player in another tab of the same browser while testing.
const KEY = 'quizclash.session';

export function loadSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

export function saveSession(s: Session | null) {
  try {
    if (s) sessionStorage.setItem(KEY, JSON.stringify(s));
    else sessionStorage.removeItem(KEY);
  } catch {
    /* storage unavailable: session just won't survive a refresh */
  }
}
