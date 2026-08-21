package com.relay.hub;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "relay.mode", havingValue = "hub")
public class WorkerHub {

    private final ObjectMapper mapper;
    private final Set<WebSocketSession> operators = ConcurrentHashMap.newKeySet();
    private volatile WebSocketSession worker;

    public WorkerHub(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void setWorker(WebSocketSession session) {
        this.worker = session;
    }

    public void clearWorker(WebSocketSession session) {
        if (this.worker == session) {
            this.worker = null;
        }
    }

    public boolean hasWorker() {
        WebSocketSession current = worker;
        return current != null && current.isOpen();
    }

    public void addOperator(WebSocketSession session) {
        operators.add(session);
    }

    public void removeOperator(WebSocketSession session) {
        operators.remove(session);
    }

    public boolean forwardToWorker(String rawText) {
        WebSocketSession current = worker;
        if (current == null || !current.isOpen()) {
            return false;
        }
        sendRaw(current, rawText);
        return true;
    }

    public void forwardToOperators(String rawText) {
        for (WebSocketSession operator : operators) {
            sendRaw(operator, rawText);
        }
    }

    public void sendJson(WebSocketSession session, Map<String, Object> payload) {
        try {
            sendRaw(session, mapper.writeValueAsString(payload));
        } catch (IOException ignored) {
        }
    }

    private void sendRaw(WebSocketSession session, String text) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(text));
            }
        } catch (IOException ignored) {
        }
    }
}
