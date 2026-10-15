package com.haanh.ragassistant.exception;

import org.springframework.http.HttpStatus;

public class AppException extends RuntimeException {
    private final HttpStatus status;

    public AppException(String message, HttpStatus status) {
        super(message);
        this.status = status;
    }

    public AppException(String message, Throwable cause, HttpStatus status) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
