package com.api.orchestrator;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedAcyclicGraph;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * In-memory cache that holds the DAG graph (task id nodes) per workflowRunId.
     * You may replace with a persistent store or Redis for horizontal scaling.
     */
    private final Map<String, DirectedAcyclicGraph<String, DefaultEdge>> dagCache = new ConcurrentHashMap<>();

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
            tr.setStatus(TaskRun.TaskStatus.PENDING);
            tr.setRetryCount(0);
            if (t.getMaxRetries() != null) tr.setMaxRetries(t.getMaxRetries());
            taskRunRepository.save(tr);
        }

        // 5) Cache DAG (keyed by run id)
        dagCache.put(run.getId(), dag);

        // 6) Enqueue root tasks (those with no incoming edges)
        Set<String> roots = dag.vertexSet().stream()
                .filter(v -> dag.incomingEdgesOf(v).isEmpty())
                .collect(Collectors.toSet());

        log.info("WorkflowRun {} created. Root tasks: {}", run.getId(), roots);
        for (String rootTaskId : roots) {
            TaskDef defTask = taskDefMap.get(rootTaskId);
            // publish minimal command/payload (workers will know how to run)
            redisPublisher.publishTask(run.getId(), rootTaskId, defTask == null ? null : defTask.getCommand());
            log.info("Enqueued root task {} for run {}", rootTaskId, run.getId());
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

        // Update TaskRun row
        TaskRun tr = taskRunRepository.findByWorkflowRunIdAndTaskId(workflowRunId, taskId);
        if (tr == null) {
            log.warn("TaskRun not found for run={}, task={}", workflowRunId, taskId);
            return;
        }

        if (success) {
            tr.setStatus(TaskRun.TaskStatus.SUCCESS);
            tr.setFinishedAt(new Date().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
            taskRunRepository.save(tr);

            // schedule dependents if ready
            DirectedAcyclicGraph<String, DefaultEdge> dag = dagCache.get(workflowRunId);
            if (dag == null) {
                log.error("DAG not found in cache for run {}. Aborting scheduling.", workflowRunId);
                return;
            }

            // For each outgoing edge from this task -> candidate child
            dag.outgoingEdgesOf(taskId).stream()
                    .map(dag::getEdgeTarget)
                    .forEach(childTaskId -> {
                        boolean allParentsSuccess = dag.incomingEdgesOf(childTaskId).stream()
                                .map(dag::getEdgeSource)
                                .allMatch(parentId -> {
                                    TaskRun parentTr = taskRunRepository.findByWorkflowRunIdAndTaskId(workflowRunId, parentId);
                                    return parentTr != null && parentTr.getStatus() == TaskRun.TaskStatus.SUCCESS;
                                });

                        if (allParentsSuccess) {
                            // find TaskRun to get command & maxRetries
                            TaskRun childTr = taskRunRepository.findByWorkflowRunIdAndTaskId(workflowRunId, childTaskId);
                            if (childTr != null && childTr.getStatus() == TaskRun.TaskStatus.PENDING) {
                                // enqueue child
                                redisPublisher.publishTask(workflowRunId, childTaskId, childTr.getCommand());
                                log.info("Enqueued dependent task {} for run {}", childTaskId, workflowRunId);
                            }
                        } else {
                            log.debug("Dependent task {} not ready yet for run {}", childTaskId, workflowRunId);
                        }
                    });

            // Check if workflow completed (no PENDING/RUNNING tasks)
            boolean allDone = taskRunRepository.findByWorkflowRunId(workflowRunId).stream()
                    .allMatch(t -> t.getStatus() == TaskRun.TaskStatus.SUCCESS);

            if (allDone) {
                WorkflowRun run = workflowRunRepository.findById(workflowRunId).orElse(null);
                if (run != null) {
                    run.setStatus(WorkflowRun.RunStatus.COMPLETED);
                    run.setFinishedAt(java.time.LocalDateTime.now());
                    workflowRunRepository.save(run);
                    log.info("WorkflowRun {} completed", workflowRunId);
                    dagCache.remove(workflowRunId); // cleanup cache
                }
            }

        } else {
            // failure handling: increment retry count, requeue if retry left, otherwise mark FAILED
            tr.setRetryCount(tr.getRetryCount() + 1);
            tr.setLastError(lastError);
            taskRunRepository.save(tr);

            if (tr.getRetryCount() <= tr.getMaxRetries()) {
                log.info("Retrying task {} for run {} (attempt {}/{})", taskId, workflowRunId, tr.getRetryCount(), tr.getMaxRetries());
                // re-publish same task; you may want to add backoff and/or delay mechanisms
                redisPublisher.publishTask(workflowRunId, taskId, tr.getCommand());
            } else {
                tr.setStatus(TaskRun.TaskStatus.FAILED);
                tr.setFinishedAt(java.time.LocalDateTime.now());
                taskRunRepository.save(tr);

                // Mark workflow as FAILED (simple fail-fast policy). You can implement other policies.
                WorkflowRun run = workflowRunRepository.findById(workflowRunId).orElse(null);
                if (run != null) {
                    run.setStatus(WorkflowRun.RunStatus.FAILED);
                    run.setFinishedAt(java.time.LocalDateTime.now());
                    workflowRunRepository.save(run);
                }
                log.warn("Task {} in run {} permanently failed after {} retries", taskId, workflowRunId, tr.getRetryCount());
                dagCache.remove(workflowRunId); // cleanup cache
            }
        }
    }
}

