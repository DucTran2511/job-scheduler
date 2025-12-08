package com.worker.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of task execution
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskExecutionResult {

    /**
     * Whether the task succeeded
     */
    private boolean success;

    /**
     * Task output (stdout)
     */
    private String output;

    /**
     * Error output (stderr)
     */
    private String errorOutput;

    /**
     * Exit code (for shell/process executors)
     */
    private Integer exitCode;

    /**
     * Error message if task failed
     */
    private String errorMessage;

    /**
     * Execution duration in milliseconds
     */
    private Long executionTimeMs;

    /**
     * Create a successful result
     */
    public static TaskExecutionResult success(String output) {
        return TaskExecutionResult.builder()
                .success(true)
                .output(output)
                .exitCode(0)
                .build();
    }

    /**
     * Create a failed result
     */
    public static TaskExecutionResult failure(String errorMessage) {
        return TaskExecutionResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .exitCode(-1)
                .build();
    }

    /**
     * Create a failed result with exit code
     */
    public static TaskExecutionResult failure(int exitCode, String errorOutput) {
        return TaskExecutionResult.builder()
                .success(false)
                .exitCode(exitCode)
                .errorOutput(errorOutput)
                .errorMessage("Process exited with code " + exitCode)
                .build();
    }
}

