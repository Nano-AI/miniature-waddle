package com.relay.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.relay.error.ApiException;

@Component
public class PathGuard {

    private final List<Path> roots;

    public PathGuard(RelayProperties properties) {
        this.roots = properties.resolveAllowedRoots();
    }

    public Path defaultRoot() {
        return roots.get(0);
    }

    public Path confine(String input) {
        if (input == null || input.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "missing 'path'");
        }
        Path candidate = Paths.get(input);
        Path absolute = candidate.isAbsolute() ? candidate : defaultRoot().resolve(candidate);
        absolute = absolute.toAbsolutePath().normalize();
        Path resolved = realOrNearest(absolute);
        if (!withinRoots(resolved)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "path is outside the allowed roots");
        }
        return resolved;
    }

    public Path confineExistingDir(String input) {
        Path dir = confine(input);
        if (!Files.isDirectory(dir)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "not a directory");
        }
        return dir;
    }

    private Path realOrNearest(Path absolute) {
        Deque<Path> tail = new ArrayDeque<>();
        Path cursor = absolute;
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            Path name = cursor.getFileName();
            if (name != null) {
                tail.push(name);
            }
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "path is outside the allowed roots");
        }
        Path real;
        try {
            real = cursor.toRealPath();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.FORBIDDEN, "path is outside the allowed roots");
        }
        for (Path segment : tail) {
            real = real.resolve(segment);
        }
        return real.normalize();
    }

    private boolean withinRoots(Path resolved) {
        for (Path root : roots) {
            if (resolved.equals(root) || resolved.startsWith(root)) {
                return true;
            }
        }
        return false;
    }
}
