package com.relay.hub;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.relay.process.OutputLine;
import com.relay.process.ProcessEventPublisher;

@Component
@ConditionalOnProperty(name = "relay.mode", havingValue = "hub")
public class NoOpPublisher implements ProcessEventPublisher {

    @Override
    public void onOutput(String process, OutputLine line) {
    }

    @Override
    public void onStarted(String process, long pid, List<String> command, String cwd) {
    }

    @Override
    public void onExited(String process, Long pid, Integer exitCode) {
    }
}
