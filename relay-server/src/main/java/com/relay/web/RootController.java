package com.relay.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.relay.process.ProcessManager;

@RestController
public class RootController {

    private final ProcessManager processes;

    public RootController(ProcessManager processes) {
        this.processes = processes;
    }

    @GetMapping("/")
    public Map<String, Object> root() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("service", "relay-server");
        doc.put("processes", processes.names());
        doc.put("endpoints", endpoints());
        doc.put("status", processes.list());
        return doc;
    }

    private List<Map<String, Object>> endpoints() {
        return List.of(
                endpoint("GET", "/", "This document plus live process status."),
                endpoint("GET", "/ws", "WebSocket channel: live output stream plus JSON action commands."),
                endpoint("GET", "/api/proc", "List managed processes (npm, gradlew)."),
                endpoint("GET", "/api/proc/{name}", "Status of one managed process."),
                endpoint("POST", "/api/proc/{name}/start", "Start a command (args:[] or command:\"...\", cwd, env). 409 if already running."),
                endpoint("POST", "/api/proc/{name}/rerun", "Re-run the last command for this process."),
                endpoint("POST", "/api/proc/{name}/input", "Write to the process stdin (data, newline)."),
                endpoint("GET", "/api/proc/{name}/output", "Read buffered terminal output (since cursor, stream filter)."),
                endpoint("POST", "/api/proc/{name}/stop", "Terminate the running process and its children."),
                endpoint("GET", "/api/files/ls", "List a directory (path)."),
                endpoint("GET", "/api/files/read", "Read a file (path, encoding)."),
                endpoint("POST", "/api/files/write", "Write a file (path, content, encoding, mode, makedirs)."),
                endpoint("POST", "/api/files/delete", "Delete a file or directory (path, recursive)."),
                endpoint("POST", "/api/files/copy", "Copy a file or directory (source, dest, recursive, replace)."),
                endpoint("POST", "/api/files/mkdir", "Create a directory (path, parents).")
        );
    }

    private Map<String, Object> endpoint(String method, String path, String description) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("method", method);
        map.put("path", path);
        map.put("description", description);
        return map;
    }
}
