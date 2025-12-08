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

/**
 * Redis-based dependency tracker using atomic counters.
 *
 * This solves two critical problems:
 * 1. N+1 Query Problem: Instead of querying DB for each dependency, we use O(1) Redis operations
 * 2. Race Condition: Redis DECR is atomic, so only one callback will see counter = 0
 *
 * For each task, we maintain a counter of pending dependencies.
 * When a parent task completes, we decrement the counter.
 * When counter reaches 0, all dependencies are satisfied → task is ready!
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisDependencyTracker {

    private final StringRedisTemplate redisTemplate;

    private static final String PENDING_DEPS_PREFIX = "pending_deps:";
    private static final String CHILDREN_PREFIX = "children:";
    private static final long WORKFLOW_TTL_HOURS = 24;

    /**
     * Initialize dependency counters for all tasks in a workflow.
     * Called when workflow starts.
     *
     * @param workflowRunId the workflow run ID
     * @param dag Map of child -> list of parent dependencies
     */
    public void initializeDependencies(String workflowRunId, Map<String, List<String>> dag) {
        if (dag == null) {
            log.debug("DAG is null for workflow {}, nothing to initialize", workflowRunId);
            return;
        }

        // Count how many tasks have dependencies
        int tasksWithDeps = 0;
        int totalTasks = dag.size();

        for (Map.Entry<String, List<String>> entry : dag.entrySet()) {
            String childTaskId = entry.getKey();
            List<String> dependencies = entry.getValue();

            if (dependencies == null || dependencies.isEmpty()) {
                // Root task - no dependencies to track
                log.debug("Task {} is a root task (no dependencies)", childTaskId);
                continue;
            }

            tasksWithDeps++;

            // Set counter = number of dependencies
            String counterKey = getPendingDepsKey(workflowRunId, childTaskId);
            redisTemplate.opsForValue().set(counterKey, String.valueOf(dependencies.size()));
            redisTemplate.expire(counterKey, WORKFLOW_TTL_HOURS, TimeUnit.HOURS);

            // For each parent, store list of children (reverse mapping)
            for (String parentTaskId : dependencies) {
                String childrenKey = getChildrenKey(workflowRunId, parentTaskId);
                redisTemplate.opsForSet().add(childrenKey, childTaskId);
                redisTemplate.expire(childrenKey, WORKFLOW_TTL_HOURS, TimeUnit.HOURS);
            }

            log.debug("Initialized dependencies for task {}: {} pending deps",
                     childTaskId, dependencies.size());
        }

        if (tasksWithDeps == 0) {
            log.info("📊 Workflow {} has {} tasks, ALL are root tasks (no dependencies to track)",
                    workflowRunId, totalTasks);
        } else {
            log.info("📊 Initialized dependency tracking for workflow {}: {} total tasks, {} with dependencies",
                    workflowRunId, totalTasks, tasksWithDeps);
        }
    }

    /**
     * Called when a task completes successfully.
     * Decrements dependency counters for all children and returns tasks that are now ready.
     *
     * This method is ATOMIC - only one caller will see counter = 0 for each task,
     * which solves the race condition problem.
     *
     * @param workflowRunId the workflow run ID
     * @param completedTaskId the task that just completed
     * @return Set of task IDs that are now ready to run (counter reached 0)
     */
    public Set<String> onTaskCompleted(String workflowRunId, String completedTaskId) {
        Set<String> readyTasks = new HashSet<>();

        // Get all children of the completed task
        String childrenKey = getChildrenKey(workflowRunId, completedTaskId);
        Set<String> children = redisTemplate.opsForSet().members(childrenKey);

        if (children == null || children.isEmpty()) {
            log.debug("Task {} has no children waiting", completedTaskId);
            return readyTasks;
        }

        log.debug("Task {} completed, checking {} children: {}", completedTaskId, children.size(), children);

        // For each child, decrement its pending dependency counter
        for (String childTaskId : children) {
            String counterKey = getPendingDepsKey(workflowRunId, childTaskId);

            // ATOMIC decrement - this is the key to solving race conditions!
            Long remaining = redisTemplate.opsForValue().decrement(counterKey);

            if (remaining == null) {
                log.warn("Counter not found for task {} (may have been cleaned up)", childTaskId);
                continue;
            }

            log.debug("Task {} now has {} pending dependencies", childTaskId, remaining);

            // If counter reached 0, all dependencies are satisfied!
            if (remaining == 0) {
                readyTasks.add(childTaskId);
                log.info("✅ Task {} is now READY (all {} dependencies satisfied)", childTaskId, children.size());
            } else if (remaining < 0) {
                // This can happen with duplicate callbacks, but idempotency check should prevent it
                log.warn("⚠️ Task {} counter went negative: {} (possible duplicate callback)",
                        childTaskId, remaining);
            }
        }

        return readyTasks;
    }

    /**
     * Clean up all dependency tracking data for a workflow.
     * Call this when workflow completes or fails.
     *
     * @param workflowRunId the workflow run ID
     */
    public void cleanup(String workflowRunId) {
        try {
            // Delete all keys matching the patterns
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

            log.info("🗑️ Cleaned up {} dependency tracking keys for workflow {}",
                    deletedCount, workflowRunId);
        } catch (Exception e) {
            log.error("Error cleaning up dependency tracking for workflow {}: {}",
                     workflowRunId, e.getMessage());
        }
    }

    /**
     * Get Redis key for pending dependency counter.
     * Format: pending_deps:WF-123:task_A
     */
    private String getPendingDepsKey(String workflowRunId, String taskId) {
        return PENDING_DEPS_PREFIX + workflowRunId + ":" + taskId;
    }

    /**
     * Get Redis key for children set.
     * Format: children:WF-123:task_A (contains set of children of task_A)
     */
    private String getChildrenKey(String workflowRunId, String taskId) {
        return CHILDREN_PREFIX + workflowRunId + ":" + taskId;
    }
}
