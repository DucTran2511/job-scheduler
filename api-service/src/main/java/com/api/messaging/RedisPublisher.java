package com.api.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class RedisPublisher {

    private final RedisTemplate<String, String> redisTemplate;
    @Value("${redis.stream.key:tasks_stream}")
    public String taskStream = "tasks_stream";

    public RedisPublisher(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }


    /**
     * Publish a task message that workers will pick up.
     * Message fields include runId, taskId, command (payload), and optional meta.
     */
    public void publishTask(String workflowRunId, String taskId, String command) {
        publishTask(workflowRunId, taskId, "SHELL", command, null, null);
    }

    /**
     * Publish a task message with specific task type.
     */
    public void publishTask(String workflowRunId, String taskId, String taskType, String command) {
        publishTask(workflowRunId, taskId, taskType, command, null, null);
    }

    /**
     * Publish a task message with full configuration.
     */
    public void publishTask(String workflowRunId, String taskId, String taskType, String command,
                           String taskName, Integer timeoutSeconds) {
        Map<String, String> fields = new HashMap<>();
        fields.put("workflowRunId", workflowRunId);
        fields.put("taskId", taskId);
        fields.put("taskType", taskType == null ? "SHELL" : taskType);
        fields.put("command", command == null ? "" : command);

        // Add optional fields
        if (taskName != null && !taskName.isEmpty()) {
            fields.put("taskName", taskName);
        }
        if (timeoutSeconds != null && timeoutSeconds > 0) {
            fields.put("timeoutSeconds", String.valueOf(timeoutSeconds));
        }

        // Add minimal metadata
        RecordId id = redisTemplate.opsForStream().add(
                StreamRecords.string(fields).withStreamKey(taskStream)
        );

        // Log for debugging
        System.out.println("📤 Published task to Redis Stream: workflowRunId=" + workflowRunId +
                          ", taskId=" + taskId + ", taskType=" + taskType + ", streamId=" + id);
    }
}
