package com.api.exception;

import lombok.Builder;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidCronExpressionException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCronExpression(InvalidCronExpressionException ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.builder()
                        .code("INVALID_CRON_EXPRESSION")
                        .message(ex.getMessage())
                        .build());
    }

    @ExceptionHandler(InvalidScheduleRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidScheduleRequest(InvalidScheduleRequestException ex) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.builder()
                        .code("INVALID_SCHEDULE_REQUEST")
                        .message(ex.getMessage())
                        .build());
    }

    @ExceptionHandler(ScheduleNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleScheduleNotFound(ScheduleNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.builder()
                        .code("SCHEDULE_NOT_FOUND")
                        .message(ex.getMessage())
                        .build());
    }

    @Data
    @Builder
    public static class ErrorResponse {
        private String code;
        private String message;
    }
}