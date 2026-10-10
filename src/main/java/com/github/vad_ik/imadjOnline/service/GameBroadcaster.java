package com.github.vad_ik.imadjOnline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.vad_ik.imadjOnline.dto.GameDtos.GameStateView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Component
public class GameBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(GameBroadcaster.class);

    private final ObjectMapper objectMapper;
    private final Map<String, Map<String, WebSocketSession>> sessionsByGame = new ConcurrentHashMap<>();

    public GameBroadcaster(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(String gameId, String clientId, WebSocketSession session) {
        sessionsByGame
                .computeIfAbsent(gameId, id -> new ConcurrentHashMap<>())
                .put(clientId, session);
    }

    public void unregister(String gameId, String clientId, WebSocketSession session) {
        Map<String, WebSocketSession> map = sessionsByGame.get(gameId);
        if (map == null) {
            return;
        }
        map.compute(clientId, (id, current) -> current == session ? null : current);
        if (map.isEmpty()) {
            sessionsByGame.remove(gameId, map);
        }
    }

    public void broadcast(String gameId, Function<String, GameStateView> stateForClient) {
        Map<String, WebSocketSession> map = sessionsByGame.get(gameId);
        if (map == null || map.isEmpty()) {
            return;
        }
        Set<Map.Entry<String, WebSocketSession>> entries = Set.copyOf(map.entrySet());
        for (Map.Entry<String, WebSocketSession> entry : entries) {
            send(entry.getValue(), stateForClient.apply(entry.getKey()));
        }
    }

    public void send(WebSocketSession session, Object payload) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "STATE",
                    "state", payload
            ));
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.debug("Failed to send WS message: {}", e.getMessage());
        }
    }
}
