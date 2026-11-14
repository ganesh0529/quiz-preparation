package com.quizclash.game.ws;

import com.quizclash.game.GameException;
import com.quizclash.game.RoomService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Locale;

/**
 * Handles {@code /ws/rooms/{code}}. The socket is push-only: clients send
 * commands over REST and receive game updates here. On connect the client
 * gets a full ROOM_STATE snapshot, so a page refresh recovers cleanly.
 */
@Component
public class RoomSocketHandler extends TextWebSocketHandler {

    private static final String ROOM_ATTR = "roomCode";

    private final RoomBroadcaster broadcaster;
    private final RoomService rooms;

    public RoomSocketHandler(RoomBroadcaster broadcaster, @Lazy RoomService rooms) {
        this.broadcaster = broadcaster;
        this.rooms = rooms;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String code = roomCodeFrom(session);
        try {
            var snapshot = rooms.view(code);
            session.getAttributes().put(ROOM_ATTR, code);
            WebSocketSession safe = broadcaster.register(code, session);
            broadcaster.sendTo(safe, "ROOM_STATE", snapshot);
        } catch (GameException e) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Unknown room"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object code = session.getAttributes().get(ROOM_ATTR);
        if (code != null) broadcaster.unregister(code.toString(), session);
    }

    private static String roomCodeFrom(WebSocketSession session) {
        String path = session.getUri() == null ? "" : session.getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1).toUpperCase(Locale.ROOT);
    }
}
