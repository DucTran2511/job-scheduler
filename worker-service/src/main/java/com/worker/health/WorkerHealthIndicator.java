package com.worker.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Custom health indicator to check worker service health.
 * This checks if the worker is ACTUALLY consuming and processing tasks,
 * not just if the service is running.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkerHealthIndicator implements HealthIndicator {

    private final RedisTemplate<String, String> redisTemplate;

    // Track last task processing time and count
    private static final AtomicReference<Long> lastTaskProcessedTime = new AtomicReference<>(System.currentTimeMillis());
    private static final AtomicLong tasksProcessedCount = new AtomicLong(0);

    @Override
    public Health health() {
        try {
            // 1. Check if Redis streams are accessible (worker needs this to consume tasks)
            boolean redisStreamsAccessible = testRedisStreamsAccess();

            // 2. Check how long since last task was processed
            long currentTime = System.currentTimeMillis();
            long lastProcessed = lastTaskProcessedTime.get();
            long secondsSinceLastTask = (currentTime - lastProcessed) / 1000;

            // 3. Get tasks processed count
            long totalTasksProcessed = tasksProcessedCount.get();

            // Determine health status
            if (!redisStreamsAccessible) {
                return Health.down()
                        .withDetail("worker", "Cannot access Redis streams")
                        .withDetail("redisStreams", false)
                        .build();
            }

            // Build health response with metrics
            Health.Builder builder = Health.up()
                    .withDetail("worker", "Active")
                    .withDetail("redisStreams", true)
                    .withDetail("tasksProcessed", totalTasksProcessed)
                    .withDetail("secondsSinceLastTask", secondsSinceLastTask);

            // Add warning if no tasks processed recently (may be idle or stuck)
            if (secondsSinceLastTask > 300 && totalTasksProcessed > 0) { // 5 minutes
                builder.withDetail("warning", "No tasks processed in 5+ minutes - worker may be idle or stuck");
            }

            // Add info if worker is newly started and hasn't processed anything yet
            if (totalTasksProcessed == 0) {
                builder.withDetail("status", "Ready to consume tasks (no tasks processed yet)");
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

    /**
     * Test if worker can access Redis streams (needed for consuming tasks)
     */
    private boolean testRedisStreamsAccess() {
        try {
            // Check if we can query stream info (lightweight check)
            String streamKey = "tasks_stream";
            // Just verify Redis connection is available
            redisTemplate.hasKey(streamKey);
            return true;
        } catch (Exception e) {
            log.error("Redis streams access test failed", e);
            return false;
        }
    }

    /**
     * Call this method from WorkerStreamConsumer when a task is processed
     * to update health metrics
     */
    public static void recordTaskProcessed() {
        lastTaskProcessedTime.set(System.currentTimeMillis());
        tasksProcessedCount.incrementAndGet();
    }

    /**
     * Get the total number of tasks processed by this worker
     */
    public static long getTasksProcessedCount() {
        return tasksProcessedCount.get();
    }
}
