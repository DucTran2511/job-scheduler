package com.api.service;

import com.common.constants.RedisTopics;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class RedisPublisher {

    private final RedisTemplate<String, String> redisTemplate;

    public void publishJob(String jobId, String task, String payload) {
        Map<String, String> data = Map.of(
                "jobId", jobId,
                "task", task,
                "payload", payload
        );

        redisTemplate.opsForStream().add(
                StreamRecords.newRecord()
                        .ofObject(data)
                        .withStreamKey(RedisTopics.JOB_STREAM)
        );
    }
}
