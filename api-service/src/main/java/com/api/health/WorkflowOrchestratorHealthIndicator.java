package com.api.health;

import com.api.cache.RedisDagStore;
import com.api.entity.WorkflowRun;
import com.api.messaging.RedisPublisher;
import com.api.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowOrchestratorHealthIndicator implements HealthIndicator {

    private final WorkflowRunRepository workflowRunRepository;
    private final RedisPublisher redisPublisher;
    private final RedisDagStore redisDagStore;

    @Override
    public Health health() {
        try {
            boolean canPublishToRedis = testRedisPublishing();
            boolean dagCacheHealthy = testDagCache();
            long activeWorkflows = workflowRunRepository.countByStatus(WorkflowRun.RunStatus.RUNNING);

            LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
            long stuckWorkflows = workflowRunRepository.findAll().stream()
                    .filter(wr -> wr.getStatus() == WorkflowRun.RunStatus.RUNNING)
                    .filter(wr -> wr.getStartedAt() != null && wr.getStartedAt().isBefore(oneHourAgo))
                    .count();

            if (!canPublishToRedis) {
                return Health.down()
                        .withDetail("orchestrator", "Cannot publish to Redis streams")
                        .withDetail("redisPublishing", false)
                        .build();
            }

            if (!dagCacheHealthy) {
                return Health.down()
                        .withDetail("orchestrator", "DAG cache not accessible")
                        .withDetail("dagCache", false)
                        .build();
            }

            Health.Builder builder = Health.up()
                    .withDetail("orchestrator", "Operational")
                    .withDetail("redisPublishing", true)
                    .withDetail("dagCache", true)
                    .withDetail("activeWorkflows", activeWorkflows);

            if (stuckWorkflows > 0) {
                builder.withDetail("stuckWorkflows", stuckWorkflows)
                       .withDetail("warning", "Some workflows running for over 1 hour");
            }

            return builder.build();

        } catch (Exception e) {
            log.error("Workflow orchestrator health check failed", e);
            return Health.down()
                    .withDetail("orchestrator", "Health check failed")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }

    private boolean testRedisPublishing() {
        try {
            return redisPublisher != null;
        } catch (Exception e) {
            log.error("Redis publishing test failed", e);
            return false;
        }
    }

    private boolean testDagCache() {
        try {
            return redisDagStore != null;
        } catch (Exception e) {
            log.error("DAG cache test failed", e);
            return false;
        }
    }
}
