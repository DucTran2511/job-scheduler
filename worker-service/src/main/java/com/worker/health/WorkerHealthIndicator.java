package com.worker.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkerHealthIndicator implements HealthIndicator {

    private final RedisTemplate<String, String> redisTemplate;

    private static final AtomicReference<Long> lastTaskProcessedTime = new AtomicReference<>(System.currentTimeMillis());
    private static final AtomicLong tasksProcessedCount = new AtomicLong(0);

    @Override
    public Health health() {
        try {
            boolean redisStreamsAccessible = testRedisStreamsAccess();

            long currentTime = System.currentTimeMillis();
            long lastProcessed = lastTaskProcessedTime.get();
            long secondsSinceLastTask = (currentTime - lastProcessed) / 1000;

            long totalTasksProcessed = tasksProcessedCount.get();

            if (!redisStreamsAccessible) {
                return Health.down()
                        .withDetail("worker", "Cannot access Redis streams")
                        .withDetail("redisStreams", false)
                        .build();
            }

            Health.Builder builder = Health.up()
                    .withDetail("worker", "Active")
                    .withDetail("redisStreams", true)
                    .withDetail("tasksProcessed", totalTasksProcessed)
                    .withDetail("secondsSinceLastTask", secondsSinceLastTask);

            if (secondsSinceLastTask > 300 && totalTasksProcessed > 0) {
                builder.withDetail("warning", "No tasks processed in 5+ minutes");
            }

            if (totalTasksProcessed == 0) {
                builder.withDetail("status", "Ready to consume tasks");
            }

            return builder.build();

        } catch (Exception e) {
            log.error("Worker health check failed", e);
            return Health.down()
                    .withDetail("worker", "Health check failed")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }

    private boolean testRedisStreamsAccess() {
        try {
            String streamKey = "tasks_stream";
            redisTemplate.hasKey(streamKey);
            return true;
        } catch (Exception e) {
            log.error("Redis streams access test failed", e);
            return false;
        }
    }

    public static void recordTaskProcessed() {
        lastTaskProcessedTime.set(System.currentTimeMillis());
        tasksProcessedCount.incrementAndGet();
    }
}
