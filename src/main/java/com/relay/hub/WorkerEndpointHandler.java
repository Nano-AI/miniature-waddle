package com.relay.hub;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
@ConditionalOnProperty(name = "relay.mode", havingValue = "hub")
public class WorkerEndpointHandler extends TextWebSocketHandler {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WorkerEndpointHandler.class);

    private final WorkerHub hub;

    public WorkerEndpointHandler(WorkerHub hub) {
        this.hub = hub;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        hub.setWorker(session);
        LOG.info("worker attached from {}", session.getRemoteAddress());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        hub.forwardToOperators(message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        hub.clearWorker(session);
        LOG.info("worker detached");
    }
}
