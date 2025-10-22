package com.api.messaging;

import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class RedisPublisher {

    private final RedisTemplate<String, String> redisTemplate;
    public static final String TASK_STREAM = "tasks_stream";

    public RedisPublisher(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }


    /**
     * Publish a task message that workers will pick up.
     * Message fields include runId, taskId, command (payload), and optional meta.
     */
    public void publishTask(String workflowRunId, String taskId, String command) {
        Map<String, String> fields = new HashMap<>();
        fields.put("workflowRunId", workflowRunId);
        fields.put("taskId", taskId);
        fields.put("command", command == null ? "" : command);

        // Add minimal metadata
        RecordId id = redisTemplate.opsForStream().add(
                StreamRecords.string(fields).withStreamKey(TASK_STREAM)
        );
    }
}
