package com.relay.ws;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;

import com.relay.config.RelayProperties;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final CommandWebSocketHandler handler;
    private final RelayProperties properties;

    public WebSocketConfig(CommandWebSocketHandler handler, RelayProperties properties) {
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
