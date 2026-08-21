package com.relay.ws;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.process.OutputLine;
import com.relay.process.ProcessEventPublisher;

@Component
public class ProcessEventBroadcaster implements ProcessEventPublisher {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper;

    public ProcessEventBroadcaster(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void register(WebSocketSession session) {
        sessions.add(session);
    }

    public void unregister(WebSocketSession session) {
        sessions.remove(session);
    }

    public void send(WebSocketSession session, Map<String, Object> payload) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            String json = mapper.writeValueAsString(payload);
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException ignored) {
        }
    }

    @Override
    public void onOutput(String process, OutputLine line) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "output");
        payload.put("process", process);
        payload.put("seq", line.seq());
        payload.put("stream", line.stream());
        payload.put("text", line.text());
        payload.put("timestamp", line.timestamp());
        broadcast(payload);
    }

    @Override
    public void onStarted(String process, long pid, List<String> command, String cwd) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "started");
        payload.put("process", process);
        payload.put("pid", pid);
        payload.put("command", command);
        payload.put("cwd", cwd);
        broadcast(payload);
    }

    @Override
    public void onExited(String process, Long pid, Integer exitCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "exited");
        payload.put("process", process);
        payload.put("pid", pid);
        payload.put("exitCode", exitCode);
        broadcast(payload);
    }

    private void broadcast(Map<String, Object> payload) {
        for (WebSocketSession session : sessions) {
            send(session, payload);
        }
    }
}
