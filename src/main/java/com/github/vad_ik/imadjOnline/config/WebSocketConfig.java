package com.github.vad_ik.imadjOnline.config;

import com.github.vad_ik.imadjOnline.websocket.GameHandshakeInterceptor;
import com.github.vad_ik.imadjOnline.websocket.GameWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GameWebSocketHandler gameWebSocketHandler;
    private final GameHandshakeInterceptor gameHandshakeInterceptor;

    public WebSocketConfig(
            GameWebSocketHandler gameWebSocketHandler,
            GameHandshakeInterceptor gameHandshakeInterceptor
    ) {
        this.gameWebSocketHandler = gameWebSocketHandler;
        this.gameHandshakeInterceptor = gameHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(gameWebSocketHandler, "/ws/game")
                .addInterceptors(gameHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
