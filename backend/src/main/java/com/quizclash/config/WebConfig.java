package com.quizclash.config;

import com.quizclash.game.ws.RoomSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebConfig implements WebMvcConfigurer, WebSocketConfigurer {

    private final RoomSocketHandler roomSocketHandler;

    public WebConfig(RoomSocketHandler roomSocketHandler) {
        this.roomSocketHandler = roomSocketHandler;
    }

    /** One WebSocket endpoint per room: ws://host/ws/rooms/{code} */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(roomSocketHandler, "/ws/rooms/*")
                .setAllowedOriginPatterns("*"); // demo project: no auth, any origin
    }

    /** The frontend normally talks to the API through a proxy; CORS helps when it doesn't. */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOriginPatterns("*").allowedMethods("*");
    }
}
