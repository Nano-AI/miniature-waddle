package com.relay.hub;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "relay.mode", havingValue = "hub")
public class HubOperatorHandler extends TextWebSocketHandler {

    private final WorkerHub hub;
    private final ObjectMapper mapper;

    public HubOperatorHandler(WorkerHub hub, ObjectMapper mapper) {
        this.hub = hub;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("type", "hello");
        hello.put("workerConnected", hub.hasWorker());
        hub.addOperator(session);
        hub.sendJson(session, hello);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (hub.forwardToWorker(message.getPayload())) {
            return;
        }
        Object id = null;
        try {
            Map<String, Object> request = mapper.readValue(message.getPayload(),
                    new TypeReference<Map<String, Object>>() {
                    });
            id = request.get("id");
        } catch (Exception ignored) {
        }
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("type", "reply");
        reply.put("id", id);
        reply.put("ok", false);
        reply.put("error", "no worker connected");
        hub.sendJson(session, reply);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        hub.removeOperator(session);
    }
}
