package com.quizclash.game.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the open WebSocket sessions of every room and pushes messages to them.
 * Every message is a JSON envelope: {@code {"type": "...", "payload": {...}}}.
 */
@Component
public class RoomBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(RoomBroadcaster.class);

    public record Envelope(String type, Object payload) {}

    /** roomCode -> (sessionId -> session) */
    private final Map<String, Map<String, WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper json;

    public RoomBroadcaster(ObjectMapper json) {
        this.json = json;
    }

    public WebSocketSession register(String roomCode, WebSocketSession raw) {
        // The decorator makes sendMessage safe to call from several threads
        // (consumer, timer, HTTP) and buffers if a client is slow.
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(raw, 5_000, 256 * 1024);
        sessions.computeIfAbsent(roomCode, k -> new ConcurrentHashMap<>()).put(raw.getId(), safe);
        return safe;
    }

    public void unregister(String roomCode, WebSocketSession session) {
        Map<String, WebSocketSession> room = sessions.get(roomCode);
        if (room != null) room.remove(session.getId());
    }

    public void send(String roomCode, String type, Object payload) {
        Map<String, WebSocketSession> room = sessions.get(roomCode);
        if (room == null || room.isEmpty()) return;
        TextMessage message = toMessage(type, payload);
        room.values().forEach(s -> sendTo(roomCode, s, message));
    }

    public void sendTo(WebSocketSession session, String type, Object payload) {
        try {
            session.sendMessage(toMessage(type, payload));
        } catch (IOException e) {
            log.debug("Could not send to session {}", session.getId());
        }
    }

    public void closeRoom(String roomCode) {
        Map<String, WebSocketSession> room = sessions.remove(roomCode);
        if (room == null) return;
        room.values().forEach(s -> {
            try {
                s.close(CloseStatus.GOING_AWAY);
            } catch (IOException ignored) {
                // already gone
            }
        });
    }

    private void sendTo(String roomCode, WebSocketSession s, TextMessage message) {
        try {
            if (s.isOpen()) s.sendMessage(message);
        } catch (Exception e) {
            log.debug("Dropping dead session {} in room {}", s.getId(), roomCode);
            unregister(roomCode, s);
        }
    }

    private TextMessage toMessage(String type, Object payload) {
        try {
            return new TextMessage(json.writeValueAsString(new Envelope(type, payload)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
