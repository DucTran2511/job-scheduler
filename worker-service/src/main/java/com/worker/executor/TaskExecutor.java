package com.worker.executor;

import com.worker.model.TaskExecutionContext;
import com.worker.model.TaskExecutionResult;

public interface TaskExecutor {

    TaskExecutionResult execute(TaskExecutionContext context);

    boolean supports(String taskType);
}
