package com.relay.worker;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.config.RelayProperties;
import com.relay.error.ApiException;
import com.relay.process.CommandDispatcher;

import jakarta.annotation.PostConstruct;

@Component
@ConditionalOnProperty(name = "relay.mode", havingValue = "worker")
public class WorkerConnection {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WorkerConnection.class);

    private final RelayProperties properties;
    private final CommandDispatcher dispatcher;
    private final WorkerSession session;
    private final ObjectMapper mapper;

    public WorkerConnection(RelayProperties properties, CommandDispatcher dispatcher,
                            WorkerSession session, ObjectMapper mapper) {
        this.properties = properties;
        this.dispatcher = dispatcher;
        this.session = session;
        this.mapper = mapper;
    }

    @PostConstruct
    public void start() {
        Thread thread = new Thread(this::connectLoop, "worker-connection");
        thread.setDaemon(true);
        thread.start();
    }

    private void connectLoop() {
        String url = properties.getHubUrl();
        if (url == null || url.isBlank()) {
            LOG.error("relay.hub-url is not set; worker cannot connect");
            return;
        }
        long backoff = 1000;
        StandardWebSocketClient client = new StandardWebSocketClient();
        while (!Thread.currentThread().isInterrupted()) {
            CountDownLatch closed = new CountDownLatch(1);
            try {
                WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
                if (properties.hasAuthToken()) {
                    headers.add("X-Auth-Token", properties.effectiveToken());
                }
                LOG.info("worker connecting to {}", url);
                client.execute(new Handler(closed), headers, URI.create(url)).get();
                backoff = 1000;
                closed.await();
                LOG.info("worker connection closed; reconnecting");
            } catch (Exception e) {
                LOG.warn("worker connect failed: {}; retry in {}ms", e.getMessage(), backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = Math.min(backoff * 2, 30000);
            }
        }
    }

    private class Handler extends TextWebSocketHandler {

        private final CountDownLatch closed;

        Handler(CountDownLatch closed) {
            this.closed = closed;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession ws) {
            session.set(ws);
            Map<String, Object> hello = new LinkedHashMap<>();
            hello.put("type", "hello");
            try {
                hello.put("processes", dispatcher.dispatch("list", Map.of()));
            } catch (Exception ignored) {
            }
            session.send(hello);
            LOG.info("worker connected");
        }

        @Override
        public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
            session.clear(ws);
            closed.countDown();
        }

        @Override
        protected void handleTextMessage(WebSocketSession ws, TextMessage message) {
            Map<String, Object> request;
            try {
                request = mapper.readValue(message.getPayload(), new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                reply(null, false, null, "invalid json");
                return;
            }
            Object id = request.get("id");
            try {
                Object result = dispatcher.dispatch(str(request.get("action")), request);
                reply(id, true, result, null);
            } catch (ApiException e) {
                reply(id, false, null, e.getMessage());
            } catch (Exception e) {
                reply(id, false, null, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        private void reply(Object id, boolean ok, Object result, String error) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "reply");
            payload.put("id", id);
            payload.put("ok", ok);
            if (result != null) {
                payload.put("result", result);
            }
            if (error != null) {
                payload.put("error", error);
            }
            session.send(payload);
        }

        private String str(Object value) {
            return value == null ? null : value.toString();
        }
    }
}
