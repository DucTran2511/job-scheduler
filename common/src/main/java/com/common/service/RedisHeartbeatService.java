package com.common.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RedisHeartbeatService {

    private final StringRedisTemplate redisTemplate;
    private static final long HEARTBEAT_TTL_SECONDS = 30;

    public void sendHeartbeat(String runId, String taskId) {
        String key = getHeartbeatKey(runId, taskId);
        redisTemplate.opsForValue().set(key, String.valueOf(System.currentTimeMillis()), HEARTBEAT_TTL_SECONDS,
                TimeUnit.SECONDS);
    }

    public boolean isAlive(String runId, String taskId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(getHeartbeatKey(runId, taskId)));
    }

    private String getHeartbeatKey(String runId, String taskId) {
        return "task:heartbeat:" + runId + ":" + taskId;
    }
}
