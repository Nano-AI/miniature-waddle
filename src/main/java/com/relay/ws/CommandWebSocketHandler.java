package com.relay.ws;

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
import com.relay.error.ApiException;
import com.relay.process.CommandDispatcher;
import com.relay.process.ProcessManager;

@Component
@ConditionalOnProperty(name = "relay.mode", havingValue = "local", matchIfMissing = true)
public class CommandWebSocketHandler extends TextWebSocketHandler {

    private final ProcessEventBroadcaster broadcaster;
    private final ProcessManager processes;
    private final CommandDispatcher dispatcher;
    private final ObjectMapper mapper;

    public CommandWebSocketHandler(ProcessEventBroadcaster broadcaster, ProcessManager processes,
                                   CommandDispatcher dispatcher, ObjectMapper mapper) {
        this.broadcaster = broadcaster;
        this.processes = processes;
        this.dispatcher = dispatcher;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        broadcaster.register(session);
        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("type", "hello");
        hello.put("processes", processes.list());
        broadcaster.send(session, hello);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broadcaster.unregister(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Map<String, Object> request;
        try {
            request = mapper.readValue(message.getPayload(), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            reply(session, null, false, null, "invalid json");
            return;
        }
        Object requestId = request.get("id");
        try {
            Object result = dispatcher.dispatch(str(request.get("action")), request);
            reply(session, requestId, true, result, null);
        } catch (ApiException e) {
            reply(session, requestId, false, null, e.getMessage());
        } catch (Exception e) {
            reply(session, requestId, false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void reply(WebSocketSession session, Object requestId, boolean ok, Object result, String error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "reply");
        payload.put("id", requestId);
        payload.put("ok", ok);
        if (result != null) {
            payload.put("result", result);
        }
        if (error != null) {
            payload.put("error", error);
        }
        broadcaster.send(session, payload);
    }

    private String str(Object value) {
        return value == null ? null : value.toString();
    }
}
