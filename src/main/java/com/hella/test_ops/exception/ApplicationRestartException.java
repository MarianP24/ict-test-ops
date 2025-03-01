package com.hella.test_ops.exception;

public class ApplicationRestartException extends RuntimeException {
    public ApplicationRestartException(String message, Throwable cause) {
        super(message, cause);
    }

    public ApplicationRestartException(String message) {
        super(message);
    }
}
