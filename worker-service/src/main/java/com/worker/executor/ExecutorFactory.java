package com.worker.executor;

import com.worker.model.TaskExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Factory for creating the appropriate task executor based on task type
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutorFactory {

    private final List<TaskExecutor> executors;

    /**
     * Get the appropriate executor for the given task type
     *
     * @param taskType The task type (SHELL, HTTP, PYTHON, DOCKER)
     * @return The executor that supports this task type
     * @throws UnsupportedTaskTypeException if no executor supports this task type
     */
    public TaskExecutor getExecutor(String taskType) {
        return executors.stream()
                .filter(executor -> executor.supports(taskType))
                .findFirst()
                .orElseThrow(() -> new UnsupportedTaskTypeException(
                        "No executor found for task type: " + taskType +
                        ". Supported types: SHELL (add HTTP, PYTHON, DOCKER executors as needed)"
                ));
    }

    /**
     * Get executor for a task context
     */
    public TaskExecutor getExecutor(TaskExecutionContext context) {
        return getExecutor(context.getTaskType());
    }

    /**
     * Exception thrown when no executor supports the requested task type
     */
    public static class UnsupportedTaskTypeException extends RuntimeException {
        public UnsupportedTaskTypeException(String message) {
            super(message);
        }
    }
}

