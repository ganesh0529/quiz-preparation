import type { GeneratedQuestion, LeaderboardEntry, RoomView } from './types';

async function request<T>(path: string, body?: unknown, method = body === undefined ? 'GET' : 'POST'): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await errorMessage(res));
  return (await res.json()) as T;
}

/** Spring returns RFC 7807 problem details: { status, detail, ... } */
async function errorMessage(res: Response): Promise<string> {
  try {
    const problem = await res.json();
    return problem.detail ?? problem.title ?? `HTTP ${res.status}`;
  } catch {
    return `HTTP ${res.status}`;
  }
}

export const api = {
  createRoom: () => request<{ code: string; hostToken: string }>('/api/rooms', {}),

  getRoom: (code: string) => request<RoomView>(`/api/rooms/${code}`),

  join: (code: string, nickname: string) =>
    request<{ playerId: string; nickname: string; code: string }>(`/api/rooms/${code}/join`, { nickname }),

  start: (code: string, hostToken: string) => request<RoomView>(`/api/rooms/${code}/start`, { hostToken }),

  next: (code: string, hostToken: string) => request<RoomView>(`/api/rooms/${code}/next-question`, { hostToken }),

  /** Goes to Kafka; the server answers 202 Accepted before scoring. */
  answer: (code: string, playerId: string, questionId: string, answerIndex: number) =>
    request<{ status: string; eventId: string }>(`/api/rooms/${code}/answers`, {
      playerId,
      questionId,
      answerIndex,
      clientTimestamp: Date.now(),
    }),

  leaderboard: (code: string) => request<LeaderboardEntry[]>(`/api/rooms/${code}/leaderboard`),

  addQuestions: (code: string, hostToken: string, questions: GeneratedQuestion[]) =>
    request<RoomView>(`/api/rooms/${code}/questions`, { hostToken, questions }),

  generateQuestions: (topic: string, count: number, difficulty: string) =>
    request<GeneratedQuestion[]>('/api/bot/questions', { topic, count, difficulty }),

  /** multipart/form-data upload; the browser sets the Content-Type boundary itself. */
  generateFromDocument: async (file: File, count: number, difficulty: string, focus: string) => {
    const form = new FormData();
    form.append('file', file);
    form.append('count', String(count));
    form.append('difficulty', difficulty);
    if (focus.trim()) form.append('focus', focus.trim());
    const res = await fetch('/api/bot/questions/from-document', { method: 'POST', body: form });
    if (!res.ok) throw new Error(await errorMessage(res));
    return (await res.json()) as GeneratedQuestion[];
  },
};

/**
 * POST + Server-Sent Events. The browser's EventSource only supports GET,
 * so we read the response body stream and parse the SSE frames ourselves.
 */
export async function streamSse(
  path: string,
  body: unknown,
  onToken: (text: string) => void,
  signal?: AbortSignal,
): Promise<void> {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(body),
    signal,
  });
  if (!res.ok || !res.body) throw new Error(await errorMessage(res));

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    // Frames are separated by a blank line.
    let sep: RegExpExecArray | null;
    while ((sep = /\r?\n\r?\n/.exec(buffer)) !== null) {
      const frame = buffer.slice(0, sep.index);
      buffer = buffer.slice(sep.index + sep[0].length);

      let event = 'message';
      const data: string[] = [];
      for (const line of frame.split(/\r?\n/)) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      }
      if (event === 'done') return;
      if (event === 'token' && data.length) onToken(JSON.parse(data.join('\n')).t ?? '');
    }
  }
}
