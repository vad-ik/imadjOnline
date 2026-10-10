package com.github.vad_ik.imadjOnline.websocket;

import com.github.vad_ik.imadjOnline.auth.ClientIdentity;
import com.github.vad_ik.imadjOnline.auth.ClientIdentityResolver;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Resolves the same client identity as HTTP (cookie + session), so WS does not
 * trust a stale clientId from the browser query string.
 */
@Component
public class GameHandshakeInterceptor implements HandshakeInterceptor {

    private final ClientIdentityResolver identityResolver;

    public GameHandshakeInterceptor(ClientIdentityResolver identityResolver) {
        this.identityResolver = identityResolver;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
        String gameId = query.getFirst("gameId");
        String queryClientId = query.getFirst("clientId");
        if (gameId == null || gameId.isBlank()) {
            return false;
        }
        attributes.put("gameId", gameId);
        if (queryClientId != null && !queryClientId.isBlank()) {
            attributes.put("queryClientId", queryClientId);
        }

        if (request instanceof ServletServerHttpRequest servletRequest
                && response instanceof ServletServerHttpResponse servletResponse) {
            ClientIdentity identity = identityResolver.resolve(
                    servletRequest.getServletRequest(),
                    servletResponse.getServletResponse()
            );
            attributes.put("clientId", identity.clientId());
            attributes.put("displayName", identity.displayName());
            attributes.put("avatarUrl", identity.avatarUrl());
            attributes.put("userId", identity.userId());
        } else if (queryClientId != null && !queryClientId.isBlank()) {
            attributes.put("clientId", queryClientId);
        } else {
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        // no-op
    }
}
