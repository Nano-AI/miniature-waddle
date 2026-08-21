package com.relay.ws;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.relay.config.RelayProperties;

@Configuration
@EnableWebSocket
@ConditionalOnProperty(name = "relay.mode", havingValue = "local", matchIfMissing = true)
public class LocalWsConfig implements WebSocketConfigurer {

    private final CommandWebSocketHandler handler;
    private final RelayProperties properties;

    public LocalWsConfig(CommandWebSocketHandler handler, RelayProperties properties) {
        this.handler = handler;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        WebSocketHandlerRegistration registration = registry.addHandler(handler, "/ws")
                .addInterceptors(new TokenHandshakeInterceptor(properties));
        List<String> origins = properties.getAllowedOrigins();
        if (origins != null && !origins.isEmpty()) {
            registration.setAllowedOrigins(origins.toArray(new String[0]));
        }
    }
}
