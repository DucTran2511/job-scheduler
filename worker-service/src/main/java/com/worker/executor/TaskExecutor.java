package com.worker.executor;

import com.worker.model.TaskExecutionContext;
import com.worker.model.TaskExecutionResult;

/**
 * Interface for task executors
 * Each executor knows how to execute a specific type of task (SHELL, HTTP, PYTHON, DOCKER)
 */
public interface TaskExecutor {

    /**
     * Execute a task
     *
     * @param context The execution context containing task details
     * @return The execution result
     */
    TaskExecutionResult execute(TaskExecutionContext context);

    /**
     * Check if this executor supports the given task type
     *
     * @param taskType The task type (SHELL, HTTP, PYTHON, DOCKER)
     * @return true if this executor can handle the task type
     */
    boolean supports(String taskType);
}

