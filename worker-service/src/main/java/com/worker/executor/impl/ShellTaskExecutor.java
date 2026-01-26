package com.worker.executor.impl;

import com.worker.executor.TaskExecutor;
import com.worker.model.TaskExecutionContext;
import com.worker.model.TaskExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.common.service.RedisHeartbeatService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

@Component
@Slf4j
@RequiredArgsConstructor
public class ShellTaskExecutor implements TaskExecutor {

    private final RedisHeartbeatService heartbeatService;

    @Override
    public TaskExecutionResult execute(TaskExecutionContext context) {
        long startTime = System.currentTimeMillis();

        ScheduledExecutorService heartbeatScheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
        ScheduledFuture<?> heartbeatFuture = null;

        try {
            heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(() -> {
                try {
                    heartbeatService.sendHeartbeat(context.getWorkflowRunId(), context.getTaskId());
                } catch (Exception e) {
                    log.error("Failed to send heartbeat", e);
                }
            }, 0, 10, TimeUnit.SECONDS);

            String command = context.getConfigString("command");
            if (command == null || command.trim().isEmpty()) {
                return TaskExecutionResult.failure("Shell command is required but not provided");
            }

            log.info("Executing shell command for task {}: {}", context.getTaskId(), command);

            ProcessBuilder processBuilder = new ProcessBuilder("bash", "-c", command);

            if (context.getWorkingDirectory() != null) {
                processBuilder.directory(new File(context.getWorkingDirectory()));
            }

            if (context.getEnvironment() != null && !context.getEnvironment().isEmpty()) {
                processBuilder.environment().putAll(context.getEnvironment());
            }

            processBuilder.redirectErrorStream(false);

            Process process = processBuilder.start();

            BufferedReader outputReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            String output = outputReader.lines().collect(Collectors.joining("\n"));
            String errorOutput = errorReader.lines().collect(Collectors.joining("\n"));

            boolean completed;
            if (context.getTimeout() != null && context.getTimeout() > 0) {
                completed = process.waitFor(context.getTimeout(), TimeUnit.MILLISECONDS);
                if (!completed) {
                    process.destroyForcibly();
                    long duration = System.currentTimeMillis() - startTime;
                    log.error("Task {} timed out after {}ms", context.getTaskId(), duration);
                    return TaskExecutionResult.builder()
                            .success(false)
                            .errorMessage("Task execution timed out after " + context.getTimeout() + "ms")
                            .executionTimeMs(duration)
                            .build();
                }
            } else {
                process.waitFor();
            }

            int exitCode = process.exitValue();
            long duration = System.currentTimeMillis() - startTime;

            TaskExecutionResult result = TaskExecutionResult.builder()
                    .success(exitCode == 0)
                    .output(output)
                    .errorOutput(errorOutput)
                    .exitCode(exitCode)
                    .executionTimeMs(duration)
                    .build();

            if (!result.isSuccess()) {
                result.setErrorMessage("Process exited with code " + exitCode);
                log.error("Task {} failed with exit code {}: {}", context.getTaskId(), exitCode, errorOutput);
            } else {
                log.info("Task {} completed successfully in {}ms", context.getTaskId(), duration);
            }

            return result;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Exception executing task {}: {}", context.getTaskId(), e.getMessage(), e);
            return TaskExecutionResult.builder()
                    .success(false)
                    .errorMessage("Exception during execution: " + e.getMessage())
                    .executionTimeMs(duration)
                    .build();
        } finally {
            if (heartbeatFuture != null) {
                heartbeatFuture.cancel(true);
            }
            heartbeatScheduler.shutdown();
        }
    }

    @Override
    public boolean supports(String taskType) {
        return "SHELL".equalsIgnoreCase(taskType) || taskType == null || taskType.trim().isEmpty();
    }
}
