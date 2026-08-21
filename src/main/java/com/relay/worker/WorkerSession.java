package com.relay.worker;

import java.io.IOException;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "relay.mode", havingValue = "worker")
public class WorkerSession {

    private final ObjectMapper mapper;
    private volatile WebSocketSession session;

    public WorkerSession(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void set(WebSocketSession session) {
        this.session = session;
    }

    public void clear(WebSocketSession closed) {
        if (this.session == closed) {
            this.session = null;
        }
    }

    public boolean isOpen() {
        WebSocketSession current = session;
        return current != null && current.isOpen();
    }

    public void send(Map<String, Object> payload) {
        WebSocketSession current = session;
        if (current == null || !current.isOpen()) {
            return;
        }
        try {
            String json = mapper.writeValueAsString(payload);
            synchronized (current) {
                current.sendMessage(new TextMessage(json));
            }
        } catch (IOException ignored) {
        }
    }
}
