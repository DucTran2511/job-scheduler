package com.api.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class DistributedLock {

    private final StringRedisTemplate redisTemplate;
    private final String instanceId = UUID.randomUUID().toString();

    public boolean tryLock(String lockName, Duration timeout) {
        String key = "lock:" + lockName;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, instanceId, timeout);

        if (Boolean.TRUE.equals(acquired)) {
            log.debug("Lock acquired: {} by instance {}", lockName, instanceId);
            return true;
        }

        log.debug("Lock busy: {} (held by another instance)", lockName);
        return false;
    }

    public void unlock(String lockName) {
        String key = "lock:" + lockName;
        String holder = redisTemplate.opsForValue().get(key);

        if (instanceId.equals(holder)) {
            redisTemplate.delete(key);
            log.debug("Lock released: {}", lockName);
        } else {
            log.warn("Cannot release lock {}: not the owner", lockName);
        }
    }

    public boolean isLocked(String lockName) {
        String key = "lock:" + lockName;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public String getInstanceId() {
        return instanceId;
    }
}
