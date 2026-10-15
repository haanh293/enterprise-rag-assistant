package com.haanh.ragassistant.exception;

import org.springframework.http.HttpStatus;

public class GeminiApiException extends AppException {
    public GeminiApiException(String message) {
        super(message, HttpStatus.BAD_GATEWAY);
    }

    public GeminiApiException(String message, Throwable cause) {
        super(message, cause, HttpStatus.BAD_GATEWAY);
    }
}
