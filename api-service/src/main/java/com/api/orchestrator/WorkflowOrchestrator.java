package com.api.orchestrator;

import com.api.cache.RedisDagStore;
import com.api.cache.RedisDependencyTracker;
import com.api.entity.TaskRun;
import com.api.entity.WorkflowEntity;
import com.api.entity.WorkflowRun;
import com.api.messaging.RedisPublisher;
import com.api.orchestrator.parser.DagParser;
import com.api.repository.TaskRunRepository;
import com.api.repository.WorkflowRepository;
import com.api.repository.WorkflowRunRepository;

import com.common.dto.DagDefinition;
import com.common.dto.TaskDef;
import com.common.enums.TaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedAcyclicGraph;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Workflow orchestrator:
 * - startWorkflow(yaml): parse, persist workflow + run + taskRuns, create DAG and enqueue root tasks
 * - onTaskCompleted(runId, taskId, success): update TaskRun, schedule dependent tasks when all parents succeeded
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowOrchestrator {

    private final DagParser dagParser;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final TaskRunRepository taskRunRepository;
    private final RedisPublisher redisPublisher;
    private final RedisDagStore redisDagCache;
    private final RedisDependencyTracker dependencyTracker;

    /**
     * Start a new workflow run by providing the YAML/JSON definition as a string.
     * Returns the created WorkflowRun.id
     */
    @Transactional
    public String startWorkflow(String yamlOrJson) throws Exception {
        // 1) Parse
        DagDefinition def = dagParser.parseDefinition(yamlOrJson);

        DirectedAcyclicGraph<String, DefaultEdge> dag = dagParser.buildGraph(def);

        // 2) Persist Workflow metadata (store raw definition)
        WorkflowEntity workflowEntity = new WorkflowEntity();
        workflowEntity.setName(def.getName() == null ? UUID.randomUUID().toString() : def.getName());
        workflowEntity.setDescription(def.getDescription());
        workflowEntity.setRawDefinition(yamlOrJson);
        workflowRepository.save(workflowEntity);

        // 3) Create WorkflowRun
        WorkflowRun run = new WorkflowRun();
        run.setWorkflow(workflowEntity);
        run.setStatus(WorkflowRun.RunStatus.RUNNING);
        workflowRunRepository.save(run);

        // 4) Create TaskRuns
        Map<String, TaskDef> taskDefMap = def.getTasks().stream()
                .collect(Collectors.toMap(TaskDef::getId, t -> t));

        for (TaskDef t : def.getTasks()) {
            TaskRun tr = new TaskRun();
            tr.setWorkflowRun(run);
            tr.setTaskId(t.getId());
            tr.setTaskName(t.getName());
            tr.setCommand(t.getCommand());
            // Set task type from definition, default to SHELL if not specified
            tr.setTaskType(TaskType.fromString(t.getTaskType()).name());
            // Set timeout from definition (null = no timeout)
            tr.setTimeoutSeconds(t.getTimeoutSeconds());
            tr.setStatus(TaskRun.TaskStatus.PENDING);
            tr.setRetryCount(0);
            if (t.getMaxRetries() != null) tr.setMaxRetries(t.getMaxRetries());
            taskRunRepository.save(tr);
        }

        // 5) Cache DAG (keyed by run id)
        redisDagCache.saveDag(run.getId(), def);

        // 5.5) Initialize dependency tracking in Redis (for O(1) dependency checks)
        Map<String, List<String>> dagMap = redisDagCache.loadDag(run.getId());
        dependencyTracker.initializeDependencies(run.getId(), dagMap);

        // 6) Enqueue root tasks (those with no incoming edges)
        Set<String> roots = dag.vertexSet().stream()
                .filter(v -> dag.incomingEdgesOf(v).isEmpty())
                .collect(Collectors.toSet());

        log.info("WorkflowRun {} created. Root tasks: {}", run.getId(), roots);
        for (String rootTaskId : roots) {
            TaskDef defTask = taskDefMap.get(rootTaskId);

            // Update root task status to RUNNING before publishing
            TaskRun rootTaskRun = taskRunRepository.findByWorkflowRunIdAndTaskId(run.getId(), rootTaskId);
            if (rootTaskRun != null) {
                rootTaskRun.setStatus(TaskRun.TaskStatus.RUNNING);
                taskRunRepository.save(rootTaskRun);
            }

            // Get task type from TaskDef, using enum for type safety
            String taskType = TaskType.fromString(defTask != null ? defTask.getTaskType() : null).name();

            // Publish task with full metadata
            redisPublisher.publishTask(
                run.getId(),
                rootTaskId,
                taskType,
                defTask == null ? null : defTask.getCommand(),
                defTask == null ? null : defTask.getName(),
                defTask == null ? null : defTask.getTimeoutSeconds()
            );
            log.info("Enqueued root task {} (type={}) for run {}", rootTaskId, taskType, run.getId());
        }

        return run.getId();
    }

    /**
     * Called when a worker reports a task completion.
     *
     * @param workflowRunId the run id
     * @param taskId the task id in the DAG
     * @param success true if task succeeded, false otherwise
     * @param lastError optional last error message
     */
    @Transactional
    public void onTaskCompleted(String workflowRunId, String taskId, boolean success, String lastError) {
        log.info("Task completed callback: run={} task={} success={} error={}", workflowRunId, taskId, success, lastError);

        // 1️⃣ Find TaskRun record
        TaskRun tr = taskRunRepository.findByWorkflowRunIdAndTaskId(workflowRunId, taskId);
        if (tr == null) {
            log.warn("TaskRun not found for run={}, task={}", workflowRunId, taskId);
            return;
        }

        // 2️⃣ Idempotency check - ignore duplicate callbacks
        if (tr.getStatus() == TaskRun.TaskStatus.SUCCESS ||
            tr.getStatus() == TaskRun.TaskStatus.FAILED) {
            log.warn("⚠️ Task {} already completed with status {}, ignoring duplicate callback",
                     taskId, tr.getStatus());
            return;
        }

        // 3️⃣ Status validation - task should be RUNNING when callback arrives
        if (tr.getStatus() != TaskRun.TaskStatus.RUNNING) {
            log.warn("⚠️ Task {} has unexpected status {} (expected RUNNING), processing anyway",
                     taskId, tr.getStatus());
            // Note: We still process the callback, but log the unexpected state for debugging
        }

        if (success) {
            tr.setStatus(TaskRun.TaskStatus.SUCCESS);
            tr.setFinishedAt(java.time.LocalDateTime.now());
            taskRunRepository.save(tr);

            // 3️⃣ Use Redis dependency tracker to find ready tasks (O(1) instead of O(n²)!)
            // This also solves race conditions - Redis DECR is atomic
            Set<String> readyTaskIds = dependencyTracker.onTaskCompleted(workflowRunId, taskId);

            log.info("🚀 Task {} completion triggered {} ready task(s): {}",
                    taskId, readyTaskIds.size(), readyTaskIds);

            // 4️⃣ Enqueue all ready tasks
            for (String readyTaskId : readyTaskIds) {
                TaskRun childTr = taskRunRepository.findByWorkflowRunIdAndTaskId(workflowRunId, readyTaskId);

                if (childTr == null) {
                    log.warn("⚠️ Ready task {} not found in database", readyTaskId);
                    continue;
                }

                if (childTr.getStatus() != TaskRun.TaskStatus.PENDING) {
                    log.warn("⚠️ Ready task {} has unexpected status: {} (expected PENDING)",
                            readyTaskId, childTr.getStatus());
                    continue;
                }

                // Mark as running and publish to Redis
                childTr.setStatus(TaskRun.TaskStatus.RUNNING);
                taskRunRepository.save(childTr);

                redisPublisher.publishTask(
                    workflowRunId,
                    readyTaskId,
                    childTr.getTaskType(),
                    childTr.getCommand(),
                    childTr.getTaskName(),
                    childTr.getTimeoutSeconds()  // Use stored timeout from TaskRun
                );

                log.info("✅ Enqueued ready task {} (type={}, timeout={}s) for run {}",
                        readyTaskId, childTr.getTaskType(), childTr.getTimeoutSeconds(), workflowRunId);
            }

            // 5️⃣ Check if all tasks completed (efficient COUNT query instead of loading all entities)
            long incompleteCount = taskRunRepository.countByWorkflowRunIdAndStatusNot(
                    workflowRunId, TaskRun.TaskStatus.SUCCESS);
            boolean allDone = (incompleteCount == 0);

            if (allDone) {
                WorkflowRun run = workflowRunRepository.findById(workflowRunId).orElse(null);
                if (run != null) {
                    run.setStatus(WorkflowRun.RunStatus.COMPLETED);
                    run.setFinishedAt(java.time.LocalDateTime.now());
                    workflowRunRepository.save(run);
                    log.info("🎉 WorkflowRun {} COMPLETED successfully", workflowRunId);

                    // Cleanup both DAG and dependency tracking from Redis
                    redisDagCache.deleteDag(workflowRunId);
                    dependencyTracker.cleanup(workflowRunId);
                }
            }

        } else {
            // ❌ Task failed
            tr.setRetryCount(tr.getRetryCount() + 1);
            tr.setLastError(lastError);
            taskRunRepository.save(tr);

            if (tr.getRetryCount() <= tr.getMaxRetries()) {
                log.info("🔁 Retrying task {} (type={}, timeout={}s) for run {} (attempt {}/{})",
                        taskId, tr.getTaskType(), tr.getTimeoutSeconds(), workflowRunId, tr.getRetryCount(), tr.getMaxRetries());

                // Publish with metadata for retry - use stored values from TaskRun
                redisPublisher.publishTask(
                    workflowRunId,
                    taskId,
                    tr.getTaskType(),
                    tr.getCommand(),
                    tr.getTaskName(),
                    tr.getTimeoutSeconds()  // Use stored timeout from TaskRun
                );
            } else {
                tr.setStatus(TaskRun.TaskStatus.FAILED);
                tr.setFinishedAt(java.time.LocalDateTime.now());
                taskRunRepository.save(tr);

                WorkflowRun run = workflowRunRepository.findById(workflowRunId).orElse(null);
                if (run != null) {
                    run.setStatus(WorkflowRun.RunStatus.FAILED);
                    run.setFinishedAt(java.time.LocalDateTime.now());
                    workflowRunRepository.save(run);
                }
                log.warn("💥 Task {} permanently failed after {} retries. Workflow {} marked FAILED.",
                        taskId, tr.getRetryCount(), workflowRunId);

                // Cleanup both DAG and dependency tracking from Redis
                redisDagCache.deleteDag(workflowRunId);
                dependencyTracker.cleanup(workflowRunId);
            }
        }
    }
}
