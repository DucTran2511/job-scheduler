package com.api.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisDependencyTracker {

    private final StringRedisTemplate redisTemplate;

    private static final String PENDING_DEPS_PREFIX = "pending_deps:";
    private static final String CHILDREN_PREFIX = "children:";
    private static final long WORKFLOW_TTL_HOURS = 24;

    public void initializeDependencies(String workflowRunId, Map<String, List<String>> dag) {
        if (dag == null) {
            log.debug("DAG is null for workflow {}, nothing to initialize", workflowRunId);
            return;
        }

        int tasksWithDeps = 0;
        int totalTasks = dag.size();

        for (Map.Entry<String, List<String>> entry : dag.entrySet()) {
            String childTaskId = entry.getKey();
            List<String> dependencies = entry.getValue();

            if (dependencies == null || dependencies.isEmpty()) {
                log.debug("Task {} is a root task (no dependencies)", childTaskId);
                continue;
            }

            tasksWithDeps++;

            String counterKey = getPendingDepsKey(workflowRunId, childTaskId);
            redisTemplate.opsForValue().set(counterKey, String.valueOf(dependencies.size()));
            redisTemplate.expire(counterKey, WORKFLOW_TTL_HOURS, TimeUnit.HOURS);

            for (String parentTaskId : dependencies) {
                String childrenKey = getChildrenKey(workflowRunId, parentTaskId);
                redisTemplate.opsForSet().add(childrenKey, childTaskId);
                redisTemplate.expire(childrenKey, WORKFLOW_TTL_HOURS, TimeUnit.HOURS);
            }

            log.debug("Initialized dependencies for task {}: {} pending deps", childTaskId, dependencies.size());
        }

        if (tasksWithDeps == 0) {
            log.info("Workflow {} has {} tasks, all are root tasks", workflowRunId, totalTasks);
        } else {
            log.info("Initialized dependency tracking for workflow {}: {} total tasks, {} with dependencies",
                    workflowRunId, totalTasks, tasksWithDeps);
        }
    }

    public Set<String> onTaskCompleted(String workflowRunId, String completedTaskId) {
        Set<String> readyTasks = new HashSet<>();

        String childrenKey = getChildrenKey(workflowRunId, completedTaskId);
        Set<String> children = redisTemplate.opsForSet().members(childrenKey);

        if (children == null || children.isEmpty()) {
            log.debug("Task {} has no children waiting", completedTaskId);
            return readyTasks;
        }

        log.debug("Task {} completed, checking {} children: {}", completedTaskId, children.size(), children);

        for (String childTaskId : children) {
            String counterKey = getPendingDepsKey(workflowRunId, childTaskId);
            Long remaining = redisTemplate.opsForValue().decrement(counterKey);

            if (remaining == null) {
                log.warn("Counter not found for task {} (may have been cleaned up)", childTaskId);
                continue;
            }

            log.debug("Task {} now has {} pending dependencies", childTaskId, remaining);

            if (remaining == 0) {
                readyTasks.add(childTaskId);
                log.info("Task {} is now READY (all dependencies satisfied)", childTaskId);
            } else if (remaining < 0) {
                log.warn("Task {} counter went negative: {} (possible duplicate callback)", childTaskId, remaining);
            }
        }

        return readyTasks;
    }

    public void cleanup(String workflowRunId) {
        try {
            Set<String> pendingKeys = redisTemplate.keys(PENDING_DEPS_PREFIX + workflowRunId + ":*");
            Set<String> childrenKeys = redisTemplate.keys(CHILDREN_PREFIX + workflowRunId + ":*");

            int deletedCount = 0;

            if (pendingKeys != null && !pendingKeys.isEmpty()) {
                redisTemplate.delete(pendingKeys);
                deletedCount += pendingKeys.size();
            }

            if (childrenKeys != null && !childrenKeys.isEmpty()) {
                redisTemplate.delete(childrenKeys);
                deletedCount += childrenKeys.size();
            }

            log.info("Cleaned up {} dependency tracking keys for workflow {}", deletedCount, workflowRunId);
        } catch (Exception e) {
            log.error("Error cleaning up dependency tracking for workflow {}: {}", workflowRunId, e.getMessage());
        }
    }

    private String getPendingDepsKey(String workflowRunId, String taskId) {
        return PENDING_DEPS_PREFIX + workflowRunId + ":" + taskId;
    }

    private String getChildrenKey(String workflowRunId, String taskId) {
        return CHILDREN_PREFIX + workflowRunId + ":" + taskId;
    }
}
