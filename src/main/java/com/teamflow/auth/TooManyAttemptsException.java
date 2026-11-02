package com.teamflow.auth;

public class TooManyAttemptsException extends RuntimeException {
    private final long retryAfter;
    public TooManyAttemptsException(long retryAfter) { super("Too many attempts. Try again later."); this.retryAfter = retryAfter; }
    public long retryAfter() { return retryAfter; }
}
