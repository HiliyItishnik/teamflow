package com.teamflow.common;

import org.springframework.http.HttpStatus;

public class RequestConditionException extends RuntimeException {
    private final HttpStatus status;
    public RequestConditionException(HttpStatus status, String message) { super(message); this.status = status; }
    public HttpStatus status() { return status; }
}
