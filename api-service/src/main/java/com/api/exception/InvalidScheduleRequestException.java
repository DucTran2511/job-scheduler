package com.api.exception;

public class InvalidScheduleRequestException extends RuntimeException {
    public InvalidScheduleRequestException(String message) {
        super(message);
    }
}
