package com.relay.worker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.relay.process.OutputLine;
import com.relay.process.ProcessEventPublisher;

@Component
@ConditionalOnProperty(name = "relay.mode", havingValue = "worker")
public class WorkerPublisher implements ProcessEventPublisher {

    private final WorkerSession session;

    public WorkerPublisher(WorkerSession session) {
        this.session = session;
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
        session.send(payload);
    }

    @Override
    public void onStarted(String process, long pid, List<String> command, String cwd) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "started");
        payload.put("process", process);
        payload.put("pid", pid);
        payload.put("command", command);
        payload.put("cwd", cwd);
        session.send(payload);
    }

    @Override
    public void onExited(String process, Long pid, Integer exitCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "exited");
        payload.put("process", process);
        payload.put("pid", pid);
        payload.put("exitCode", exitCode);
        session.send(payload);
    }
}
