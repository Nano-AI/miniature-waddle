package com.relay.ws;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import com.relay.config.RelayProperties;

public class TokenHandshakeInterceptor implements HandshakeInterceptor {

    private final RelayProperties properties;

    public TokenHandshakeInterceptor(RelayProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!properties.hasAuthToken()) {
            return true;
        }
        boolean ok = properties.tokenMatches(request.getHeaders().getFirst("X-Auth-Token"));
        if (!ok) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
        }
        return ok;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                              WebSocketHandler wsHandler, Exception exception) {
    }
}
