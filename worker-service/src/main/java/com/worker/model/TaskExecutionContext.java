package com.worker.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Context object containing all information needed to execute a task
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskExecutionContext {

    /**
     * Workflow run ID
     */
    private String workflowRunId;

    /**
     * Task ID
     */
    private String taskId;

    /**
     * Task type (SHELL, HTTP, PYTHON, DOCKER)
     */
    private String taskType;

    /**
     * Task configuration (executor-specific)
     */
    @Builder.Default
    private Map<String, Object> config = new HashMap<>();

    /**
     * Task timeout in milliseconds (optional)
     */
    private Long timeout;

    /**
     * Working directory for task execution
     */
    private String workingDirectory;

    /**
     * Environment variables for task execution
     */
    @Builder.Default
    private Map<String, String> environment = new HashMap<>();

    /**
     * Get configuration value as String
     */
    public String getConfigString(String key) {
        Object value = config.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * Get configuration value with default
     */
    public String getConfigString(String key, String defaultValue) {
        String value = getConfigString(key);
        return value != null ? value : defaultValue;
    }
}

