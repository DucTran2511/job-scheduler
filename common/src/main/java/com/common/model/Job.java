package com.common.model;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class Job {
    private String id;
    private String task;
    private String payload;

    private JobStatus status;
    private int retryCount;
    private int maxRetries = 3;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
    private String lastError;
    public enum JobStatus {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED
    }
}
