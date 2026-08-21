package com.relay.error;

import org.springframework.http.HttpStatus;

public class ProcessBusyException extends ApiException {

    public ProcessBusyException(String name) {
        super(HttpStatus.CONFLICT, "process '" + name + "' is already running; stop it before starting a new command");
    }
}
