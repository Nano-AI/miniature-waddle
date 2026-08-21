package com.relay.ws;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.TextMessage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.error.ApiException;
import com.relay.files.FileService;
import com.relay.process.ProcessManager;

@Component
public class CommandWebSocketHandler extends TextWebSocketHandler {

    private final ProcessEventBroadcaster broadcaster;
    private final ProcessManager processes;
    private final FileService files;
    private final ObjectMapper mapper;

    public CommandWebSocketHandler(ProcessEventBroadcaster broadcaster, ProcessManager processes,
                                   FileService files, ObjectMapper mapper) {
        this.broadcaster = broadcaster;
        this.processes = processes;
        this.files = files;
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
        String action = str(request.get("action"));
        Object requestId = request.get("id");
        try {
            Object result = dispatch(action, request);
            reply(session, requestId, true, result, null);
        } catch (ApiException e) {
            reply(session, requestId, false, null, e.getMessage());
        } catch (Exception e) {
            reply(session, requestId, false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Object dispatch(String action, Map<String, Object> request) {
        if (action == null) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "missing 'action'");
        }
        String process = str(request.get("process"));
        switch (action) {
            case "list":
                return processes.list();
            case "status":
                return processes.get(process).status();
            case "start":
                return processes.start(process,
                        (List<String>) request.get("args"),
                        str(request.get("command")),
                        str(request.get("cwd")),
                        (Map<String, String>) request.get("env"));
            case "rerun":
                return processes.rerun(process);
            case "input":
                processes.writeInput(process, str(request.getOrDefault("data", "")),
                        !Boolean.FALSE.equals(request.get("newline")));
                return Map.of("process", process, "written", true);
            case "output":
                return processes.output(process, asLong(request.get("since")), str(request.get("stream")));
            case "stop":
                return processes.stop(process);
            case "ls":
                return files.list(str(request.get("path")));
            case "read":
                return files.read(str(request.get("path")), str(request.get("encoding")));
            case "write":
                return files.write(str(request.get("path")), str(request.get("content")),
                        str(request.get("encoding")), str(request.get("mode")),
                        Boolean.TRUE.equals(request.get("makedirs")));
            case "delete":
                return files.delete(str(request.get("path")), Boolean.TRUE.equals(request.get("recursive")));
            case "copy":
                return files.copy(str(request.get("source")), str(request.get("dest")),
                        Boolean.TRUE.equals(request.get("recursive")), Boolean.TRUE.equals(request.get("replace")));
            case "mkdir":
                return files.mkdir(str(request.get("path")), !Boolean.FALSE.equals(request.get("parents")));
            default:
                throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "unknown action: " + action);
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

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
