package com.haanh.ragassistant.exception;

import org.springframework.http.HttpStatus;

public class DocumentProcessingException extends AppException {
    public DocumentProcessingException(String message) {
        super(message, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    public DocumentProcessingException(String message, Throwable cause) {
        super(message, cause, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
