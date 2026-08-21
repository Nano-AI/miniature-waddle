package com.relay.error;

import org.springframework.http.HttpStatus;

public class UnknownProcessException extends ApiException {

    public UnknownProcessException(String name) {
        super(HttpStatus.NOT_FOUND, "unknown process: " + name);
    }
}
