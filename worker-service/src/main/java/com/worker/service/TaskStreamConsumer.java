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
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service that consumes tasks from Redis Stream and executes them
 */
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

    /**
     * Start the worker thread on application startup
     */
    @PostConstruct
    public void startConsumer() {
        if (consumerName == null || consumerName.trim().isEmpty()) {
            consumerName = "worker-" + UUID.randomUUID().toString().substring(0, 8);
        }

        Thread consumerThread = new Thread(this::consumeTasksLoop, "TaskStreamConsumer");
        consumerThread.setDaemon(true);
        consumerThread.start();

        log.info("🚀 Task stream consumer started: group={}, consumer={}, stream={}",
                consumerGroup, consumerName, streamKey);
    }

    /**
     * Main consumer loop - continuously polls Redis Stream for tasks
     */
    private void consumeTasksLoop() {
        // Ensure consumer group exists
        ensureConsumerGroupExists();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Read messages from stream
                List<MapRecord<String, Object, Object>> messages = readMessagesFromStream();

                log.info("Message comming bro: messages={}", messages);

                if (messages == null || messages.isEmpty()) {
                    continue;
                }

                // Process each message
                for (MapRecord<String, Object, Object> message : messages) {
                    processTaskMessage(message);
                    acknowledgeMessage(message);
                }

            } catch (Exception e) {
                log.error("❌ Error in consumer loop: {}", e.getMessage(), e);
                sleepOnError();
            }
        }

        log.warn("Task stream consumer stopped");
    }

    /**
     * Ensure the Redis Stream consumer group exists
     */
    private void ensureConsumerGroupExists() {
        try {
            // First, check if the stream exists by trying to get its length
            // If stream doesn't exist, we need to create it first
            try {
                redisTemplate.opsForStream().size(streamKey);
            } catch (Exception e) {
                // Stream doesn't exist yet - it will be created when first message is published
                log.info("📭 Stream '{}' doesn't exist yet, will be created when first message arrives", streamKey);
                return;
            }

            // Stream exists, now create consumer group
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.latest(), consumerGroup);
            log.info("✅ Created Redis Stream consumer group: {}", consumerGroup);
        } catch (Exception e) {
            // Group already exists or stream doesn't exist yet - both are fine
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group '{}' already exists", consumerGroup);
            } else if (e.getMessage() != null && e.getMessage().contains("no such key")) {
                log.debug("Stream '{}' doesn't exist yet", streamKey);
            } else {
                log.debug("Error ensuring consumer group exists: {}", e.getMessage());
            }
        }
    }

    /**
     * Read messages from Redis Stream
     */
    @SuppressWarnings("unchecked")
    private List<MapRecord<String, Object, Object>> readMessagesFromStream() {
        try {
            // Cast is safe because RedisTemplate<String, String> returns MapRecord with String values
            return (List<MapRecord<String, Object, Object>>) (List<?>) redisTemplate.opsForStream().read(
                    Consumer.from(consumerGroup, consumerName),
                    StreamReadOptions.empty().block(Duration.ofSeconds(pollTimeoutSeconds)),
                    StreamOffset.create(streamKey, ReadOffset.lastConsumed())
            );
        } catch (Exception e) {
            // If stream or consumer group doesn't exist, try to create it
            if (e.getMessage() != null &&
                (e.getMessage().contains("NOGROUP") || e.getMessage().contains("no such key"))) {
                log.debug("Stream or consumer group not ready yet, will retry...");
                ensureConsumerGroupExists();
                return null;
            }
            throw e;
        }
    }

    /**
     * Process a task message from the stream
     */
    private void processTaskMessage(MapRecord<String, Object, Object> record) {
        try {
            // Extract task data from message
            Map<Object, Object> messageData = record.getValue();

            // Log raw message for debugging
            log.info("📦 Raw message data: {}", messageData);
            log.info("📦 Message keys: {}", messageData.keySet());

            String workflowRunId = getStringValue(messageData, "workflowRunId");
            String taskId = getStringValue(messageData, "taskId");
            String taskType = getStringValue(messageData, "taskType");

            // Also try "command" field for backward compatibility
            String command = getStringValue(messageData, "command");

            log.info("📦 Extracted values - workflowRunId: {}, taskId: {}, taskType: {}, command: {}",
                    workflowRunId, taskId, taskType, command);

            // Default to SHELL if taskType is not provided
            if (taskType == null || taskType.trim().isEmpty()) {
                taskType = "SHELL";
            }

            log.info("👷 Worker received task: workflowRunId={}, taskId={}, type={}",
                    workflowRunId, taskId, taskType);

            // Build execution context
            TaskExecutionContext context = buildExecutionContext(messageData, workflowRunId, taskId, taskType);

            // Execute task
            TaskExecutionResult result = executeTask(context);

            // Update health metrics
            WorkerHealthIndicator.recordTaskProcessed();

            // Report completion back to orchestrator
            reportTaskCompletion(workflowRunId, taskId, result);

        } catch (Exception e) {
            log.error("❌ Error processing task message: {}", e.getMessage(), e);
        }
    }

    /**
     * Safely extract String value from message data
     */
    private String getStringValue(Map<Object, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    /**
     * Build task execution context from message data
     */
    private TaskExecutionContext buildExecutionContext(Map<Object, Object> messageData,
                                                       String workflowRunId,
                                                       String taskId,
                                                       String taskType) {
        // Extract config
        Map<String, Object> config = new HashMap<>();

        // For backward compatibility: if "command" is present at root level, use it
        if (messageData.containsKey("command")) {
            config.put("command", getStringValue(messageData, "command"));
        }

        // If config map is provided, merge it
        if (messageData.containsKey("config") && messageData.get("config") instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> configMap = (Map<String, Object>) messageData.get("config");
            config.putAll(configMap);
        }

        // Extract timeout if present
        Long timeout = null;
        if (messageData.containsKey("timeout")) {
            try {
                timeout = Long.parseLong(messageData.get("timeout").toString());
            } catch (NumberFormatException e) {
                log.warn("Invalid timeout value: {}", messageData.get("timeout"));
            }
        }
        // Also check for timeoutSeconds field
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

    /**
     * Execute the task using the appropriate executor
     */
    private TaskExecutionResult executeTask(TaskExecutionContext context) {
        try {
            // Get the appropriate executor
            TaskExecutor executor = executorFactory.getExecutor(context.getTaskType());

            log.info("Executing task {} with {} executor",
                    context.getTaskId(), context.getTaskType());

            // Execute the task
            TaskExecutionResult result = executor.execute(context);
            result.setSuccess(Boolean.TRUE);
            if (result.isSuccess()) {
                log.info("✅ Task {} completed successfully in {}ms",
                        context.getTaskId(), result.getExecutionTimeMs());
            } else {
                log.error("❌ Task {} failed: {}",
                         context.getTaskId(), result.getErrorMessage());
            }

            return result;

        } catch (ExecutorFactory.UnsupportedTaskTypeException e) {
            log.error("❌ Unsupported task type '{}' for task {}",
                     context.getTaskType(), context.getTaskId());
            return TaskExecutionResult.failure("Unsupported task type: " + context.getTaskType());

        } catch (Exception e) {
            log.error("❌ Unexpected error executing task {}: {}",
                     context.getTaskId(), e.getMessage(), e);
            return TaskExecutionResult.failure("Execution error: " + e.getMessage());
        }
    }

    /**
     * Report task completion to orchestrator
     */
    private void reportTaskCompletion(String workflowRunId, String taskId, TaskExecutionResult result) {
        callbackService.reportTaskCompletion(
                workflowRunId,
                taskId,
                result.isSuccess(),
                result.getErrorMessage()
        );
    }

    /**
     * Acknowledge message in Redis Stream
     */
    private void acknowledgeMessage(MapRecord<String, Object, Object> message) {
        try {
            redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, message.getId());
            log.debug("Acknowledged message: {}", message.getId());
        } catch (Exception e) {
            log.error("Failed to acknowledge message {}: {}", message.getId(), e.getMessage());
        }
    }

    /**
     * Sleep after error to avoid tight loop
     */
    private void sleepOnError() {
        try {
            Thread.sleep(errorSleepMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
