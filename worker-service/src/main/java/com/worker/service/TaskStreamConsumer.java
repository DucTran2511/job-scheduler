package com.worker.service;

import com.worker.executor.ExecutorFactory;
import com.worker.executor.TaskExecutor;
import com.worker.health.WorkerHealthIndicator;
import com.worker.model.TaskExecutionContext;
import com.worker.model.TaskExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
@Slf4j
public class TaskStreamConsumer {

    private final RedisTemplate<String, String> redisTemplate;
    private final ExecutorFactory executorFactory;
    private final OrchestratorCallbackService callbackService;

    @Value("${worker.stream.key:tasks_stream}")
    private String streamKey;

    @Value("${worker.consumer.group:worker-group}")
    private String consumerGroup;

    @Value("${worker.consumer.name:}")
    private String consumerName;

    @Value("${worker.poll.timeout-seconds:2}")
    private int pollTimeoutSeconds;

    @Value("${worker.error.sleep-ms:1000}")
    private long errorSleepMs;

    @Value("${worker.virtual-threads.max-concurrent:1000}")
    private int maxConcurrentTasks;

    private ExecutorService virtualThreadExecutor;
    private Semaphore taskSemaphore;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger activeVirtualThreads = new AtomicInteger(0);

    @PostConstruct
    public void startConsumer() {
        if (consumerName == null || consumerName.trim().isEmpty()) {
            consumerName = "worker-" + UUID.randomUUID().toString().substring(0, 8);
        }

        virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        taskSemaphore = new Semaphore(maxConcurrentTasks);
        Thread.startVirtualThread(this::consumeTasksLoop);

        log.info("Task stream consumer started: group={}, consumer={}, stream={}, maxConcurrent={}",
                consumerGroup, consumerName, streamKey, maxConcurrentTasks);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down Task Stream Consumer...");
        running.set(false);

        if (virtualThreadExecutor != null) {
            virtualThreadExecutor.shutdown();
            try {
                if (!virtualThreadExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    virtualThreadExecutor.shutdownNow();
                    log.warn("Forced shutdown after timeout");
                }
            } catch (InterruptedException e) {
                virtualThreadExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("Task Stream Consumer shutdown complete. Final active threads: {}", activeVirtualThreads.get());
    }

    private void consumeTasksLoop() {
        ensureConsumerGroupExists();

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                List<MapRecord<String, Object, Object>> messages = readMessagesFromStream();

                if (messages == null || messages.isEmpty()) {
                    continue;
                }

                log.debug("Received {} messages from stream", messages.size());

                for (MapRecord<String, Object, Object> message : messages) {
                    submitTaskForProcessing(message);
                }

            } catch (Exception e) {
                if (running.get()) {
                    log.error("Error in consumer loop: {}", e.getMessage(), e);
                    sleepOnError();
                }
            }
        }

        log.info("Consumer loop stopped");
    }

    private void submitTaskForProcessing(MapRecord<String, Object, Object> message) {
        virtualThreadExecutor.submit(() -> {
            try {
                taskSemaphore.acquire();
                int currentActive = activeVirtualThreads.incrementAndGet();
                log.debug("Virtual thread started. Active: {}", currentActive);

                try {
                    processTaskMessage(message);
                    acknowledgeMessage(message);
                } finally {
                    activeVirtualThreads.decrementAndGet();
                    taskSemaphore.release();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Task processing interrupted for message: {}", message.getId());
            } catch (Exception e) {
                log.error("Error processing task in virtual thread: {}", e.getMessage(), e);
            }
        });
    }

    private void ensureConsumerGroupExists() {
        try {
            try {
                redisTemplate.opsForStream().size(streamKey);
            } catch (Exception e) {
                log.info("Stream '{}' doesn't exist yet, will be created when first message arrives", streamKey);
                return;
            }

            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.latest(), consumerGroup);
            log.info("Created Redis Stream consumer group: {}", consumerGroup);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group '{}' already exists", consumerGroup);
            } else if (e.getMessage() != null && e.getMessage().contains("no such key")) {
                log.debug("Stream '{}' doesn't exist yet", streamKey);
            } else {
                log.debug("Error ensuring consumer group exists: {}", e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<MapRecord<String, Object, Object>> readMessagesFromStream() {
        try {
            return (List<MapRecord<String, Object, Object>>) (List<?>) redisTemplate.opsForStream().read(
                    Consumer.from(consumerGroup, consumerName),
                    StreamReadOptions.empty().block(Duration.ofSeconds(pollTimeoutSeconds)),
                    StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
        } catch (Exception e) {
            if (e.getMessage() != null &&
                    (e.getMessage().contains("NOGROUP") || e.getMessage().contains("no such key"))) {
                log.debug("Stream or consumer group not ready yet, will retry...");
                ensureConsumerGroupExists();
                return null;
            }
            throw e;
        }
    }

    private void processTaskMessage(MapRecord<String, Object, Object> record) {
        long startTime = System.currentTimeMillis();
        String threadName = Thread.currentThread().toString();

        try {
            Map<Object, Object> messageData = record.getValue();

            String workflowRunId = getStringValue(messageData, "workflowRunId");
            String taskId = getStringValue(messageData, "taskId");
            String taskType = getStringValue(messageData, "taskType");

            if (taskType == null || taskType.trim().isEmpty()) {
                taskType = "SHELL";
            }

            log.info("[{}] Processing task: workflowRunId={}, taskId={}, type={}", threadName, workflowRunId, taskId,
                    taskType);

            TaskExecutionContext context = buildExecutionContext(messageData, workflowRunId, taskId, taskType);
            TaskExecutionResult result = executeTask(context);

            WorkerHealthIndicator.recordTaskProcessed();
            reportTaskCompletion(workflowRunId, taskId, result);

            long duration = System.currentTimeMillis() - startTime;
            log.info("[{}] Task {} completed in {}ms", threadName, taskId, duration);

        } catch (Exception e) {
            log.error("Error processing task message: {}", e.getMessage(), e);
        }
    }

    private String getStringValue(Map<Object, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    private TaskExecutionContext buildExecutionContext(Map<Object, Object> messageData,
            String workflowRunId,
            String taskId,
            String taskType) {
        Map<String, Object> config = new HashMap<>();

        if (messageData.containsKey("command")) {
            config.put("command", getStringValue(messageData, "command"));
        }

        if (messageData.containsKey("config") && messageData.get("config") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> configMap = (Map<String, Object>) messageData.get("config");
            config.putAll(configMap);
        }

        Long timeout = null;
        if (messageData.containsKey("timeout")) {
            try {
                timeout = Long.parseLong(messageData.get("timeout").toString());
            } catch (NumberFormatException e) {
                log.warn("Invalid timeout value: {}", messageData.get("timeout"));
            }
        }
        if (messageData.containsKey("timeoutSeconds")) {
            try {
                timeout = Long.parseLong(messageData.get("timeoutSeconds").toString());
            } catch (NumberFormatException e) {
                log.warn("Invalid timeoutSeconds value: {}", messageData.get("timeoutSeconds"));
            }
        }

        return TaskExecutionContext.builder()
                .workflowRunId(workflowRunId)
                .taskId(taskId)
                .taskType(taskType)
                .config(config)
                .timeout(timeout)
                .build();
    }

    private TaskExecutionResult executeTask(TaskExecutionContext context) {
        try {
            TaskExecutor executor = executorFactory.getExecutor(context.getTaskType());
            log.info("Executing task {} with {} executor", context.getTaskId(), context.getTaskType());

            TaskExecutionResult result = executor.execute(context);
            result.setSuccess(Boolean.TRUE);

            if (result.isSuccess()) {
                log.info("Task {} completed successfully in {}ms", context.getTaskId(), result.getExecutionTimeMs());
            } else {
                log.error("Task {} failed: {}", context.getTaskId(), result.getErrorMessage());
            }

            return result;

        } catch (ExecutorFactory.UnsupportedTaskTypeException e) {
            log.error("Unsupported task type '{}' for task {}", context.getTaskType(), context.getTaskId());
            return TaskExecutionResult.failure("Unsupported task type: " + context.getTaskType());

        } catch (Exception e) {
            log.error("Unexpected error executing task {}: {}", context.getTaskId(), e.getMessage(), e);
            return TaskExecutionResult.failure("Execution error: " + e.getMessage());
        }
    }

    private void reportTaskCompletion(String workflowRunId, String taskId, TaskExecutionResult result) {
        callbackService.reportTaskCompletion(workflowRunId, taskId, result.isSuccess(), result.getErrorMessage());
    }

    private void acknowledgeMessage(MapRecord<String, Object, Object> message) {
        try {
            redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, message.getId());
            log.debug("Acknowledged message: {}", message.getId());
        } catch (Exception e) {
            log.error("Failed to acknowledge message {}: {}", message.getId(), e.getMessage());
        }
    }

    private void sleepOnError() {
        try {
            Thread.sleep(errorSleepMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public int getActiveVirtualThreadCount() {
        return activeVirtualThreads.get();
    }

    public int getAvailablePermits() {
        return taskSemaphore.availablePermits();
    }
}
