package com.relay.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.relay.process.ProcessManager;
import com.relay.web.dto.InputRequest;
import com.relay.web.dto.StartRequest;

@RestController
@RequestMapping("/api/proc")
public class ProcessController {

    private final ProcessManager manager;

    public ProcessController(ProcessManager manager) {
        this.manager = manager;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return manager.list();
    }

    @GetMapping("/{name}")
    public Map<String, Object> status(@PathVariable String name) {
        return manager.get(name).status();
    }

    @PostMapping("/{name}/start")
    public Map<String, Object> start(@PathVariable String name, @RequestBody(required = false) StartRequest request) {
        StartRequest req = request == null ? new StartRequest() : request;
        return manager.start(name, req.args, req.command, req.cwd, req.env);
    }

    @PostMapping("/{name}/rerun")
    public Map<String, Object> rerun(@PathVariable String name) {
        return manager.rerun(name);
    }

    @PostMapping("/{name}/input")
    public Map<String, Object> input(@PathVariable String name, @RequestBody InputRequest request) {
        boolean newline = request.newline == null || request.newline;
        manager.writeInput(name, request.data == null ? "" : request.data, newline);
        return Map.of("name", name, "written", true);
    }

    @GetMapping("/{name}/output")
    public Map<String, Object> output(@PathVariable String name,
                                      @RequestParam(defaultValue = "0") long since,
                                      @RequestParam(required = false) String stream) {
        return manager.output(name, since, stream);
    }

    @PostMapping("/{name}/stop")
    public Map<String, Object> stop(@PathVariable String name) {
        return manager.stop(name);
    }
}
