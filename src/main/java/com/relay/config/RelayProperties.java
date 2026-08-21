package com.relay.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.annotation.PostConstruct;

@ConfigurationProperties(prefix = "relay")
public class RelayProperties {

    private String baseDir = "";
    private String npmExecutable = "";
    private String gradlewExecutable = "";
    private int maxOutputLines = 2000;
    private int maxLineChars = 8192;
    private long maxRequestBytes = 10_485_760L;
    private String mode = "local";
    private String hubUrl = "";
    private long remoteTimeoutMs = 60000;
    private String authToken = "";
    private String authTokenFile = "";
    private volatile String resolvedToken;
    private List<String> allowedRoots = new ArrayList<>();
    private List<String> allowedOrigins = new ArrayList<>();
    private List<String> envBlocklist = new ArrayList<>(List.of(
            "TOKEN", "SECRET", "KEY", "PASSWORD", "PASSWD", "CREDENTIAL", "AWS_", "AZURE", "PRIVATE"));

    public String getBaseDir() {
        return baseDir;
    }

    public void setBaseDir(String baseDir) {
        this.baseDir = baseDir;
    }

    public String getNpmExecutable() {
        return npmExecutable;
    }

    public void setNpmExecutable(String npmExecutable) {
        this.npmExecutable = npmExecutable;
    }

    public String getGradlewExecutable() {
        return gradlewExecutable;
    }

    public void setGradlewExecutable(String gradlewExecutable) {
        this.gradlewExecutable = gradlewExecutable;
    }

    public int getMaxOutputLines() {
        return maxOutputLines;
    }

    public void setMaxOutputLines(int maxOutputLines) {
        this.maxOutputLines = maxOutputLines;
    }

    public int getMaxLineChars() {
        return maxLineChars;
    }

    public void setMaxLineChars(int maxLineChars) {
        this.maxLineChars = maxLineChars;
    }

    public long getMaxRequestBytes() {
        return maxRequestBytes;
    }

    public void setMaxRequestBytes(long maxRequestBytes) {
        this.maxRequestBytes = maxRequestBytes;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getHubUrl() {
        return hubUrl;
    }

    public void setHubUrl(String hubUrl) {
        this.hubUrl = hubUrl;
    }

    public long getRemoteTimeoutMs() {
        return remoteTimeoutMs;
    }

    public void setRemoteTimeoutMs(long remoteTimeoutMs) {
        this.remoteTimeoutMs = remoteTimeoutMs;
    }

    public boolean isHub() {
        return "hub".equalsIgnoreCase(mode);
    }

    public boolean isWorker() {
        return "worker".equalsIgnoreCase(mode);
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public String getAuthTokenFile() {
        return authTokenFile;
    }

    public void setAuthTokenFile(String authTokenFile) {
        this.authTokenFile = authTokenFile;
    }

    public List<String> getAllowedRoots() {
        return allowedRoots;
    }

    public void setAllowedRoots(List<String> allowedRoots) {
        this.allowedRoots = allowedRoots;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public List<String> getEnvBlocklist() {
        return envBlocklist;
    }

    public void setEnvBlocklist(List<String> envBlocklist) {
        this.envBlocklist = envBlocklist;
    }

    @PostConstruct
    public void resolveToken() {
        String token = authToken == null ? "" : authToken;
        if (authTokenFile != null && !authTokenFile.isBlank()) {
            try {
                token = Files.readString(Path.of(authTokenFile)).trim();
            } catch (IOException e) {
                throw new IllegalStateException("cannot read relay.auth-token-file: " + authTokenFile, e);
            }
        }
        this.resolvedToken = token;
    }

    public String effectiveToken() {
        if (resolvedToken == null) {
            resolveToken();
        }
        return resolvedToken;
    }

    public boolean hasAuthToken() {
        String token = effectiveToken();
        return token != null && !token.isBlank();
    }

    public boolean tokenMatches(String supplied) {
        if (!hasAuthToken()) {
            return true;
        }
        if (supplied == null) {
            return false;
        }
        byte[] a = effectiveToken().getBytes(StandardCharsets.UTF_8);
        byte[] b = supplied.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public Path resolveBaseDir() {
        if (baseDir == null || baseDir.isBlank()) {
            return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        }
        return Paths.get(baseDir).toAbsolutePath().normalize();
    }

    public List<Path> resolveAllowedRoots() {
        List<Path> roots = new ArrayList<>();
        if (allowedRoots == null || allowedRoots.isEmpty()) {
            roots.add(canonical(resolveBaseDir()));
            return roots;
        }
        for (String entry : allowedRoots) {
            if (entry != null && !entry.isBlank()) {
                roots.add(canonical(Paths.get(entry).toAbsolutePath().normalize()));
            }
        }
        if (roots.isEmpty()) {
            roots.add(canonical(resolveBaseDir()));
        }
        return roots;
    }

    private Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.normalize();
        }
    }

    public List<String> npmCommand() {
        if (npmExecutable != null && !npmExecutable.isBlank()) {
            return List.of(npmExecutable);
        }
        return List.of(isWindows() ? "npm.cmd" : "npm");
    }

    public String gradlewName() {
        if (gradlewExecutable != null && !gradlewExecutable.isBlank()) {
            return gradlewExecutable;
        }
        return isWindows() ? "gradlew.bat" : "gradlew";
    }
}
