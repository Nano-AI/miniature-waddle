package com.relay.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.relay.files.FileService;
import com.relay.web.dto.CopyRequest;
import com.relay.web.dto.DeleteRequest;
import com.relay.web.dto.MkdirRequest;
import com.relay.web.dto.WriteRequest;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileService files;

    public FileController(FileService files) {
        this.files = files;
    }

    @GetMapping("/ls")
    public Map<String, Object> ls(@RequestParam String path) {
        return files.list(path);
    }

    @GetMapping("/read")
    public Map<String, Object> read(@RequestParam String path,
                                    @RequestParam(required = false) String encoding) {
        return files.read(path, encoding);
    }

    @PostMapping("/write")
    public Map<String, Object> write(@RequestBody WriteRequest request) {
        boolean makedirs = request.makedirs != null && request.makedirs;
        return files.write(request.path, request.content, request.encoding, request.mode, makedirs);
    }

    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestBody DeleteRequest request) {
        boolean recursive = request.recursive != null && request.recursive;
        return files.delete(request.path, recursive);
    }

    @PostMapping("/copy")
    public Map<String, Object> copy(@RequestBody CopyRequest request) {
        boolean recursive = request.recursive != null && request.recursive;
        boolean replace = request.replace != null && request.replace;
        return files.copy(request.source, request.dest, recursive, replace);
    }

    @PostMapping("/mkdir")
    public Map<String, Object> mkdir(@RequestBody MkdirRequest request) {
        boolean parents = request.parents == null || request.parents;
        return files.mkdir(request.path, parents);
    }
}
