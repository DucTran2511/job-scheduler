package com.worker.executor;

import com.worker.model.TaskExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutorFactory {

    private final List<TaskExecutor> executors;

    public TaskExecutor getExecutor(String taskType) {
        return executors.stream()
                .filter(executor -> executor.supports(taskType))
                .findFirst()
                .orElseThrow(() -> new UnsupportedTaskTypeException(
                        "No executor found for task type: " + taskType +
                        ". Supported types: SHELL (add HTTP, PYTHON, DOCKER executors as needed)"
                ));
    }

    public TaskExecutor getExecutor(TaskExecutionContext context) {
        return getExecutor(context.getTaskType());
    }

    public static class UnsupportedTaskTypeException extends RuntimeException {
        public UnsupportedTaskTypeException(String message) {
            super(message);
        }
    }
}
