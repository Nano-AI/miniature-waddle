package com.relay.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class StartupInfo {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(StartupInfo.class);

    private final RelayProperties properties;
    private final Environment environment;

    public StartupInfo(RelayProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        String mode = properties.getMode();
        LOG.info("relay ready: mode={} listen={}:{} auth={} roots={}",
                mode,
                environment.getProperty("server.address", "0.0.0.0"),
                environment.getProperty("server.port", "8090"),
                properties.hasAuthToken() ? "on" : "off",
                properties.resolveAllowedRoots());
        if (properties.isWorker()) {
            String hubUrl = properties.getHubUrl();
            LOG.info("relay worker target: hub-url={}", hubUrl == null || hubUrl.isBlank() ? "(not set!)" : hubUrl);
        }
    }
}
