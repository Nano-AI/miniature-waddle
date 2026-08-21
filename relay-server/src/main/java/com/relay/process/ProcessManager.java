package com.relay.process;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.relay.config.PathGuard;
import com.relay.config.RelayProperties;
import com.relay.error.UnknownProcessException;

@Service
public class ProcessManager {

    private final Map<String, ManagedProcess> processes = new LinkedHashMap<>();

    public ProcessManager(RelayProperties properties, PathGuard guard, ProcessEventPublisher publisher) {
        processes.put("npm", new ManagedProcess(
                "npm",
                properties.npmCommand(),
                false,
                properties.getMaxOutputLines(),
                properties.getMaxLineChars(),
                guard,
                properties.getEnvBlocklist(),
                publisher));
        processes.put("gradlew", new ManagedProcess(
                "gradlew",
                List.of(properties.gradlewName()),
                true,
                properties.getMaxOutputLines(),
                properties.getMaxLineChars(),
                guard,
                properties.getEnvBlocklist(),
                publisher));
    }

    public ManagedProcess get(String name) {
        ManagedProcess process = processes.get(name);
        if (process == null) {
            throw new UnknownProcessException(name);
        }
        return process;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ManagedProcess process : processes.values()) {
            out.add(process.status());
        }
        return out;
    }

    public List<String> names() {
        return new ArrayList<>(processes.keySet());
    }

    public Map<String, Object> start(String name, List<String> args, String command, String cwd, Map<String, String> env) {
        List<String> resolvedArgs = resolveArgs(args, command);
        return get(name).start(resolvedArgs, cwd, env);
    }

    public Map<String, Object> rerun(String name) {
        return get(name).rerun();
    }

    public void writeInput(String name, String data, boolean newline) {
        get(name).writeInput(data, newline);
    }

    public Map<String, Object> stop(String name) {
        return get(name).stop();
    }

    public Map<String, Object> output(String name, long since, String stream) {
        ManagedProcess process = get(name);
        Map<String, Object> result = new LinkedHashMap<>();
        List<OutputLine> lines = process.readOutput(since, stream);
        result.put("name", name);
        result.put("running", process.isRunning());
        result.put("lines", lines);
        long latest = since;
        for (OutputLine line : lines) {
            if (line.seq() > latest) {
                latest = line.seq();
            }
        }
        result.put("nextCursor", latest);
        return result;
    }

    private List<String> resolveArgs(List<String> args, String command) {
        if (args != null && !args.isEmpty()) {
            return args;
        }
        if (command != null && !command.isBlank()) {
            return CommandTokenizer.split(command);
        }
        return List.of();
    }
}
