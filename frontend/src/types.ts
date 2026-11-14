// Mirrors com.quizclash.game.view.Views on the backend.

export type Phase = 'LOBBY' | 'QUESTION_OPEN' | 'QUESTION_RESULTS' | 'FINISHED';

export interface PlayerView {
  id: string;
  nickname: string;
  score: number;
}

export interface QuestionView {
  id: string;
  index: number;
  total: number;
  question: string;
  options: string[];
  timeLimitMs: number;
  remainingMs: number;
}

export interface LeaderboardEntry {
  rank: number;
  playerId: string;
  nickname: string;
  score: number;
  correctCount: number;
}

export interface PlayerResult {
  playerId: string;
  nickname: string;
  answerIndex: number | null;
  correct: boolean;
  points: number;
  usedHint: boolean;
}

export interface QuestionResults {
  questionId: string;
  index: number;
  correctIndex: number;
  distribution: number[];
  results: PlayerResult[];
  leaderboard: LeaderboardEntry[];
}

export interface RoomView {
  code: string;
  phase: Phase;
  players: PlayerView[];
  questionCount: number;
  currentQuestion: QuestionView | null;
  answeredCount: number;
  lastResults: QuestionResults | null;
  leaderboard: LeaderboardEntry[];
}

export interface GeneratedQuestion {
  question: string;
  options: string[];
  correctIndex: number;
  /** Passage from the uploaded document that proves the answer (document questions only). */
  source?: string | null;
}

/** Messages pushed over /ws/rooms/{code}: {"type": ..., "payload": ...} */
export type ServerMessage =
  | { type: 'ROOM_STATE' | 'ROOM_UPDATE'; payload: RoomView }
  | { type: 'QUESTION_OPEN'; payload: QuestionView }
  | { type: 'ANSWER_PROGRESS'; payload: { questionId: string; answered: number; total: number } }
  | { type: 'QUESTION_RESULTS'; payload: QuestionResults }
  | { type: 'GAME_ENDED'; payload: { leaderboard: LeaderboardEntry[] } };

export interface Session {
  code: string;
  role: 'host' | 'player';
  hostToken?: string;
  playerId?: string;
  nickname?: string;
}
