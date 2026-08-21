package com.relay.error;

import org.springframework.http.HttpStatus;

public class ProcessNotRunningException extends ApiException {

    public ProcessNotRunningException(String name) {
        super(HttpStatus.CONFLICT, "process '" + name + "' is not running");
    }
}
