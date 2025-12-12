package com.worker.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskExecutionResult {

    private boolean success;
    private String output;
    private String errorOutput;
    private Integer exitCode;
    private String errorMessage;
    private Long executionTimeMs;

    public static TaskExecutionResult success(String output) {
        return TaskExecutionResult.builder()
                .success(true)
                .output(output)
                .exitCode(0)
                .build();
    }

    public static TaskExecutionResult failure(String errorMessage) {
        return TaskExecutionResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .exitCode(-1)
                .build();
    }

    public static TaskExecutionResult failure(int exitCode, String errorOutput) {
        return TaskExecutionResult.builder()
                .success(false)
                .exitCode(exitCode)
                .errorOutput(errorOutput)
                .errorMessage("Process exited with code " + exitCode)
                .build();
    }
}
