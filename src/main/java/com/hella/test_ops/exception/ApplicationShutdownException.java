package com.hella.test_ops.exception;

public class ApplicationShutdownException extends RuntimeException {
   public ApplicationShutdownException(String message, Throwable cause) {
        super(message, cause);
    }
}
