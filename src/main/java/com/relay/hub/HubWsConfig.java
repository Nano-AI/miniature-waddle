package com.relay.hub;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.relay.config.RelayProperties;
import com.relay.ws.TokenHandshakeInterceptor;

@Configuration
@EnableWebSocket
@ConditionalOnProperty(name = "relay.mode", havingValue = "hub")
public class HubWsConfig implements WebSocketConfigurer {

    private final HubOperatorHandler operatorHandler;
    private final WorkerEndpointHandler workerHandler;
    private final RelayProperties properties;

    public HubWsConfig(HubOperatorHandler operatorHandler, WorkerEndpointHandler workerHandler,
                       RelayProperties properties) {
        this.operatorHandler = operatorHandler;
        this.workerHandler = workerHandler;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        TokenHandshakeInterceptor interceptor = new TokenHandshakeInterceptor(properties);
        WebSocketHandlerRegistration operator = registry.addHandler(operatorHandler, "/ws")
                .addInterceptors(interceptor);
        registry.addHandler(workerHandler, "/worker").addInterceptors(interceptor);
        List<String> origins = properties.getAllowedOrigins();
        if (origins != null && !origins.isEmpty()) {
            operator.setAllowedOrigins(origins.toArray(new String[0]));
        }
    }
}
