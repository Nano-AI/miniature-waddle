package com.relay.process;

import java.util.List;

public interface ProcessEventPublisher {

    void onOutput(String process, OutputLine line);

    void onStarted(String process, long pid, List<String> command, String cwd);

    void onExited(String process, Long pid, Integer exitCode);
}
