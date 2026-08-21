package com.relay.process;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.relay.config.PathGuard;
import com.relay.error.ProcessBusyException;
import com.relay.error.ProcessNotRunningException;

public class ManagedProcess {

    private final String name;
    private final List<String> baseCommand;
    private final boolean cwdRelativeExecutable;
    private final int maxLines;
    private final int maxLineChars;
    private final PathGuard guard;
    private final List<String> envBlocklist;
    private final ProcessEventPublisher publisher;

    private final Object lifecycleLock = new Object();
    private final Object stdinLock = new Object();
    private final Deque<OutputLine> buffer = new ArrayDeque<>();
    private final AtomicLong sequence = new AtomicLong(0);

    private volatile Process process;
    private volatile boolean running;
    private volatile Integer exitCode;
    private volatile Long pid;
    private volatile Instant startedAt;
    private volatile Instant endedAt;
    private volatile List<String> lastArgs = List.of();
    private volatile String lastCwd;
    private volatile List<String> lastCommand = List.of();
    private BufferedWriter stdin;

    public ManagedProcess(String name, List<String> baseCommand, boolean cwdRelativeExecutable,
                          int maxLines, int maxLineChars, PathGuard guard,
                          List<String> envBlocklist, ProcessEventPublisher publisher) {
        this.name = name;
        this.baseCommand = List.copyOf(baseCommand);
        this.cwdRelativeExecutable = cwdRelativeExecutable;
        this.maxLines = maxLines;
        this.maxLineChars = maxLineChars;
        this.guard = guard;
        this.envBlocklist = envBlocklist;
        this.publisher = publisher;
    }

    public String getName() {
        return name;
    }

    public boolean isRunning() {
        return running;
    }

    public Map<String, Object> start(List<String> args, String cwd, Map<String, String> env) {
        synchronized (lifecycleLock) {
            if (running) {
                throw new ProcessBusyException(name);
            }
            List<String> requestedArgs = args == null ? List.of() : List.copyOf(args);
            Path dir = resolveDir(cwd);
            List<String> command = buildCommand(requestedArgs, dir);

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(dir.toFile());
            builder.redirectErrorStream(false);
            Map<String, String> childEnv = builder.environment();
            scrubEnv(childEnv);
            if (env != null && !env.isEmpty()) {
                childEnv.putAll(env);
            }

            Process started;
            try {
                started = builder.start();
            } catch (IOException e) {
                throw new UncheckedIOException("failed to start " + name + ": " + e.getMessage(), e);
            }

            this.process = started;
            this.running = true;
            this.exitCode = null;
            this.endedAt = null;
            this.pid = started.pid();
            this.startedAt = Instant.now();
            this.lastArgs = requestedArgs;
            this.lastCwd = dir.toString();
            this.lastCommand = command;
            this.stdin = new BufferedWriter(new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8));

            pump(started.getInputStream(), "stdout");
            pump(started.getErrorStream(), "stderr");
            waitFor(started, this.pid);

            publisher.onStarted(name, this.pid, command, dir.toString());
            return status();
        }
    }

    public Map<String, Object> rerun() {
        if (lastArgs == null) {
            throw new ProcessNotRunningException(name);
        }
        return start(lastArgs, lastCwd, null);
    }

    public void writeInput(String data, boolean newline) {
        Process current = process;
        BufferedWriter writer = stdin;
        if (current == null || !running || writer == null) {
            throw new ProcessNotRunningException(name);
        }
        synchronized (stdinLock) {
            try {
                writer.write(data);
                if (newline) {
                    writer.newLine();
                }
                writer.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("failed to write stdin for " + name + ": " + e.getMessage(), e);
            }
        }
    }

    public Map<String, Object> stop() {
        Process current;
        synchronized (lifecycleLock) {
            current = process;
            if (current == null || !running) {
                Map<String, Object> result = status();
                result.put("stopped", false);
                result.put("alreadyExited", true);
                return result;
            }
        }
        current.descendants().forEach(ProcessHandle::destroy);
        current.destroy();
        try {
            if (!current.waitFor(5, TimeUnit.SECONDS)) {
                current.descendants().forEach(ProcessHandle::destroyForcibly);
                current.destroyForcibly();
                current.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Map<String, Object> result = status();
        result.put("stopped", true);
        return result;
    }

    public List<OutputLine> readOutput(long since, String streamFilter) {
        List<OutputLine> out = new ArrayList<>();
        synchronized (buffer) {
            for (OutputLine line : buffer) {
                if (line.seq() > since && (streamFilter == null || streamFilter.equals(line.stream()))) {
                    out.add(line);
                }
            }
        }
        return out;
    }

    public Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("running", running);
        map.put("pid", pid);
        map.put("exitCode", exitCode);
        map.put("lastArgs", lastArgs);
        map.put("lastCommand", lastCommand);
        map.put("cwd", lastCwd);
        map.put("startedAt", startedAt == null ? null : startedAt.toString());
        map.put("endedAt", endedAt == null ? null : endedAt.toString());
        map.put("latestSeq", sequence.get());
        synchronized (buffer) {
            map.put("bufferedLines", buffer.size());
        }
        return map;
    }

    private List<String> buildCommand(List<String> args, Path dir) {
        List<String> command = new ArrayList<>();
        if (cwdRelativeExecutable) {
            String exe = baseCommand.get(0);
            command.add(dir.resolve(exe).toString());
            command.addAll(baseCommand.subList(1, baseCommand.size()));
        } else {
            command.addAll(baseCommand);
        }
        command.addAll(args);
        return command;
    }

    private Path resolveDir(String cwd) {
        if (cwd == null || cwd.isBlank()) {
            return guard.defaultRoot();
        }
        return guard.confineExistingDir(cwd);
    }

    private void scrubEnv(Map<String, String> environment) {
        if (envBlocklist == null || envBlocklist.isEmpty()) {
            return;
        }
        environment.keySet().removeIf(this::isBlockedEnv);
    }

    private boolean isBlockedEnv(String key) {
        String upper = key.toUpperCase();
        for (String token : envBlocklist) {
            if (token != null && !token.isBlank() && upper.contains(token.toUpperCase())) {
                return true;
            }
        }
        return false;
    }

    private void pump(InputStream stream, String tag) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    append(tag, line);
                }
            } catch (IOException ignored) {
            }
        }, name + "-" + tag);
        thread.setDaemon(true);
        thread.start();
    }

    private void append(String stream, String text) {
        String value = (maxLineChars > 0 && text.length() > maxLineChars) ? text.substring(0, maxLineChars) : text;
        OutputLine line = new OutputLine(sequence.incrementAndGet(), stream, value, System.currentTimeMillis());
        synchronized (buffer) {
            buffer.addLast(line);
            while (buffer.size() > maxLines) {
                buffer.removeFirst();
            }
        }
        publisher.onOutput(name, line);
    }

    private void waitFor(Process started, Long startedPid) {
        Thread thread = new Thread(() -> {
            int code = -1;
            try {
                code = started.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (lifecycleLock) {
                if (process == started) {
                    running = false;
                    exitCode = code;
                    endedAt = Instant.now();
                }
            }
            publisher.onExited(name, startedPid, code);
        }, name + "-waiter");
        thread.setDaemon(true);
        thread.start();
    }
}
