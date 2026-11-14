import { useEffect, useRef, useState } from 'react';
import type { ServerMessage } from './types';

/**
 * Subscribes to /ws/rooms/{code}. Reconnects with backoff if the connection
 * drops; on every (re)connect the server sends a full ROOM_STATE snapshot.
 */
export function useRoomSocket(code: string | undefined, onMessage: (m: ServerMessage) => void) {
  const handler = useRef(onMessage);
  handler.current = onMessage;
  const [connected, setConnected] = useState(false);
  const [unknownRoom, setUnknownRoom] = useState(false);

  useEffect(() => {
    if (!code) return;
    let ws: WebSocket | null = null;
    let retry = 0;
    let timer: number | undefined;
    let stopped = false;

    const connect = () => {
      const proto = location.protocol === 'https:' ? 'wss' : 'ws';
      ws = new WebSocket(`${proto}://${location.host}/ws/rooms/${code}`);
      ws.onopen = () => {
        retry = 0;
        setConnected(true);
      };
      ws.onmessage = (e) => handler.current(JSON.parse(e.data) as ServerMessage);
      ws.onclose = (e) => {
        setConnected(false);
        if (stopped) return;
        if (e.code === 1008) {
          setUnknownRoom(true); // server closed with POLICY_VIOLATION: room doesn't exist
          return;
        }
        timer = window.setTimeout(connect, Math.min(5000, 500 * 2 ** retry++));
      };
    };
    connect();

    return () => {
      stopped = true;
      window.clearTimeout(timer);
      ws?.close();
    };
  }, [code]);

  return { connected, unknownRoom };
}
