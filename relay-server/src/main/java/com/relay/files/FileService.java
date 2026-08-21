package com.relay.files;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.relay.config.PathGuard;
import com.relay.error.ApiException;

@Service
public class FileService {

    private final PathGuard guard;

    public FileService(PathGuard guard) {
        this.guard = guard;
    }

    public Map<String, Object> list(String path) {
        Path dir = resolve(path);
        if (!Files.isDirectory(dir)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no such directory: " + dir);
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            children.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(child -> entries.add(describe(child)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", dir.toString());
        result.put("entries", entries);
        return result;
    }

    public Map<String, Object> read(String path, String encoding) {
        Path file = resolve(path);
        if (!Files.isRegularFile(file)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no such file: " + file);
        }
        if (encoding != null && !encoding.equals("utf-8") && !encoding.equals("base64")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "encoding must be 'utf-8' or 'base64'");
        }
        byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", file.toString());
        result.put("size", raw.length);
        if (!"base64".equals(encoding)) {
            try {
                String text = new String(raw, StandardCharsets.UTF_8);
                if (isValidUtf8(raw, text)) {
                    result.put("encoding", "utf-8");
                    result.put("content", text);
                    return result;
                }
                if ("utf-8".equals(encoding)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "file is not valid utf-8: " + file);
                }
            } catch (RuntimeException e) {
                if ("utf-8".equals(encoding)) {
                    throw e;
                }
            }
        }
        result.put("encoding", "base64");
        result.put("content", Base64.getEncoder().encodeToString(raw));
        return result;
    }

    public Map<String, Object> write(String path, String content, String encoding, String mode, boolean makedirs) {
        Path file = resolve(path);
        if (content == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "missing 'content'");
        }
        String enc = encoding == null ? "utf-8" : encoding;
        byte[] raw;
        if ("base64".equals(enc)) {
            raw = Base64.getDecoder().decode(content);
        } else if ("utf-8".equals(enc)) {
            raw = content.getBytes(StandardCharsets.UTF_8);
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "encoding must be 'utf-8' or 'base64'");
        }
        String writeMode = mode == null ? "overwrite" : mode;
        try {
            if (makedirs && file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            if ("append".equals(writeMode)) {
                Files.write(file, raw, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else if ("overwrite".equals(writeMode)) {
                Files.write(file, raw, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                throw new ApiException(HttpStatus.BAD_REQUEST, "mode must be 'overwrite' or 'append'");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", file.toString());
        result.put("bytesWritten", raw.length);
        result.put("mode", writeMode);
        return result;
    }

    public Map<String, Object> delete(String path, boolean recursive) {
        Path target = resolve(path);
        if (!Files.exists(target)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no such path: " + target);
        }
        boolean isDir = Files.isDirectory(target);
        try {
            if (isDir) {
                if (!recursive) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "'" + target + "' is a directory; pass recursive:true to delete it");
                }
                deleteRecursively(target);
            } else {
                Files.delete(target);
            }
        } catch (DirectoryNotEmptyException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "'" + target + "' is a directory; pass recursive:true to delete it");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", target.toString());
        result.put("deleted", true);
        result.put("wasDir", isDir);
        return result;
    }

    public Map<String, Object> copy(String source, String dest, boolean recursive, boolean replace) {
        Path from = resolve(source);
        Path to = resolve(dest);
        if (!Files.exists(from)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no such source: " + from);
        }
        try {
            if (Files.isDirectory(from)) {
                if (!recursive) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "source is a directory; pass recursive:true to copy it");
                }
                copyDirectory(from, to, replace);
            } else {
                if (to.getParent() != null) {
                    Files.createDirectories(to.getParent());
                }
                if (replace) {
                    Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.copy(from, to);
                }
            }
        } catch (FileAlreadyExistsException e) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "destination exists: " + e.getFile() + " (pass replace:true to overwrite)");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", from.toString());
        result.put("dest", to.toString());
        result.put("copied", true);
        return result;
    }

    public Map<String, Object> mkdir(String path, boolean parents) {
        Path dir = resolve(path);
        try {
            if (parents) {
                Files.createDirectories(dir);
            } else {
                Files.createDirectory(dir);
            }
        } catch (FileAlreadyExistsException e) {
            throw new ApiException(HttpStatus.CONFLICT, "already exists: " + dir);
        } catch (NoSuchFileException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "parent does not exist: " + dir + " (pass parents:true to create it)");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", dir.toString());
        result.put("created", true);
        return result;
    }

    private Path resolve(String path) {
        return guard.confine(path);
    }

    private Map<String, Object> describe(Path child) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", child.getFileName().toString());
        try {
            BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class);
            entry.put("isDir", attrs.isDirectory());
            entry.put("size", attrs.size());
            entry.put("modified", attrs.lastModifiedTime().toMillis());
        } catch (IOException e) {
            entry.put("isDir", Files.isDirectory(child));
            entry.put("size", null);
            entry.put("modified", null);
        }
        return entry;
    }

    private void copyDirectory(Path from, Path to, boolean replace) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path src : (Iterable<Path>) walk::iterator) {
                Path rel = from.relativize(src);
                Path dst = to.resolve(rel);
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    if (dst.getParent() != null) {
                        Files.createDirectories(dst.getParent());
                    }
                    if (replace) {
                        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.copy(src, dst);
                    }
                }
            }
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private boolean isValidUtf8(byte[] raw, String decoded) {
        return raw.length == decoded.getBytes(StandardCharsets.UTF_8).length
                && !decoded.contains("�");
    }
}
