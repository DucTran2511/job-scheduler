package com.api.exception;

public class InvalidCronExpressionException extends RuntimeException {
    public InvalidCronExpressionException(String message) {
        super(message);
    }
}
