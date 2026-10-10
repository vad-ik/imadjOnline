package com.github.vad_ik.imadjOnline.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.vad_ik.imadjOnline.dto.GameDtos.WsIncoming;
import com.github.vad_ik.imadjOnline.exception.ApiException;
import com.github.vad_ik.imadjOnline.service.GameBroadcaster;
import com.github.vad_ik.imadjOnline.service.LobbyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GameWebSocketHandler.class);

    private final LobbyService lobbyService;
    private final GameBroadcaster broadcaster;
    private final ObjectMapper objectMapper;

    public GameWebSocketHandler(
            LobbyService lobbyService,
            GameBroadcaster broadcaster,
            ObjectMapper objectMapper
    ) {
        this.lobbyService = lobbyService;
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String gameId = (String) session.getAttributes().get("gameId");
        String clientId = (String) session.getAttributes().get("clientId");
        if (gameId == null || gameId.isBlank()) {
            gameId = queryParam(session, "gameId");
        }
        if (clientId == null || clientId.isBlank()) {
            clientId = queryParam(session, "clientId");
        }
        if (gameId == null || gameId.isBlank() || clientId == null || clientId.isBlank()) {
            closeQuietly(session, CloseStatus.BAD_DATA);
            return;
        }
        String queryClientId = (String) session.getAttributes().get("queryClientId");
        try {
            String seatId = lobbyService.ensureJoinedForWs(
                    gameId,
                    clientId,
                    queryClientId,
                    (Long) session.getAttributes().get("userId"),
                    (String) session.getAttributes().get("displayName"),
                    (String) session.getAttributes().get("avatarUrl")
            );
            session.getAttributes().put("gameId", gameId);
            session.getAttributes().put("clientId", seatId);
            broadcaster.register(gameId, seatId, session);
            lobbyService.onWsConnected(gameId, seatId);
            broadcaster.send(session, lobbyService.stateFor(gameId, seatId));
        } catch (ApiException e) {
            log.info("WS reject game={} client={} query={} err={}",
                    gameId, clientId, queryClientId, e.getError());
            sendError(session, e.getError(), e.getMessage());
            closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String gameId = (String) session.getAttributes().get("gameId");
        String clientId = (String) session.getAttributes().get("clientId");
        try {
            WsIncoming incoming = objectMapper.readValue(message.getPayload(), WsIncoming.class);
            String type = incoming.type() == null ? "" : incoming.type().toUpperCase();
            switch (type) {
                case "SET_ASSOCIATION" -> lobbyService.submitAssociation(
                        gameId, clientId, incoming.association(), incoming.cardId());
                case "PLAY_CARD" -> lobbyService.playCard(gameId, clientId, incoming.cardId());
                case "VOTE" -> lobbyService.vote(
                        gameId,
                        clientId,
                        incoming.voteCardId() != null ? incoming.voteCardId() : incoming.cardId()
                );
                case "NEXT_ROUND" -> lobbyService.nextRound(gameId, clientId);
                case "PING" -> broadcaster.send(session, lobbyService.stateFor(gameId, clientId));
                default -> sendError(session, "unknown_type", "Unknown message type");
            }
        } catch (ApiException e) {
            sendError(session, e.getError(), e.getMessage());
        } catch (Exception e) {
            log.warn("WS message failed", e);
            sendError(session, "bad_message", "Cannot process message");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String gameId = (String) session.getAttributes().get("gameId");
        String clientId = (String) session.getAttributes().get("clientId");
        if (gameId != null && clientId != null) {
            broadcaster.unregister(gameId, clientId, session);
            lobbyService.onWsDisconnected(gameId, clientId);
        }
    }

    private void sendError(WebSocketSession session, String error, String message) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "ERROR",
                    "error", error,
                    "message", message
            ));
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static String queryParam(WebSocketSession session, String name) {
        URI uri = session.getUri();
        if (uri == null) {
            return null;
        }
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst(name);
    }
}
