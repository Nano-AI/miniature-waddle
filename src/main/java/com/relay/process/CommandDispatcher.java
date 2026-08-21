package com.relay.process;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.relay.error.ApiException;
import com.relay.files.FileService;

@Component
public class CommandDispatcher {

    private final ProcessManager processes;
    private final FileService files;

    public CommandDispatcher(ProcessManager processes, FileService files) {
        this.processes = processes;
        this.files = files;
    }

    @SuppressWarnings("unchecked")
    public Object dispatch(String action, Map<String, Object> request) {
        if (action == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "missing 'action'");
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
                throw new ApiException(HttpStatus.BAD_REQUEST, "unknown action: " + action);
        }
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
