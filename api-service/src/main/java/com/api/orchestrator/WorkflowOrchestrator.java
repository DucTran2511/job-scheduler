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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.stream.Collectors;

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

    @Transactional
    public String startWorkflow(String yamlOrJson) throws Exception {
        DagDefinition def = dagParser.parseDefinition(yamlOrJson);
        DirectedAcyclicGraph<String, DefaultEdge> dag = dagParser.buildGraph(def);

        WorkflowEntity workflowEntity = new WorkflowEntity();
        workflowEntity.setName(def.getName() == null ? UUID.randomUUID().toString() : def.getName());
        workflowEntity.setDescription(def.getDescription());
        workflowEntity.setRawDefinition(yamlOrJson);
        workflowRepository.save(workflowEntity);

        WorkflowRun run = new WorkflowRun();
        run.setWorkflow(workflowEntity);
        run.setStatus(WorkflowRun.RunStatus.RUNNING);
        workflowRunRepository.save(run);

        Map<String, TaskDef> taskDefMap = def.getTasks().stream()
                .collect(Collectors.toMap(TaskDef::getId, t -> t));

        for (TaskDef t : def.getTasks()) {
            TaskRun tr = new TaskRun();
            tr.setWorkflowRun(run);
            tr.setTaskId(t.getId());
            tr.setTaskName(t.getName());
            tr.setCommand(t.getCommand());
            tr.setTaskType(TaskType.fromString(t.getTaskType()).name());
            tr.setTimeoutSeconds(t.getTimeoutSeconds());
            tr.setStatus(TaskRun.TaskStatus.PENDING);
            tr.setRetryCount(0);
            if (t.getMaxRetries() != null)
                tr.setMaxRetries(t.getMaxRetries());
            taskRunRepository.save(tr);
        }

        redisDagCache.saveDag(run.getId(), def);

        Map<String, List<String>> dagMap = redisDagCache.loadDag(run.getId());
        dependencyTracker.initializeDependencies(run.getId(), dagMap);

        Set<String> roots = dag.vertexSet().stream()
                .filter(v -> dag.incomingEdgesOf(v).isEmpty())
                .collect(Collectors.toSet());

        log.info("WorkflowRun {} created. Root tasks: {}", run.getId(), roots);
        for (String rootTaskId : roots) {
            TaskDef defTask = taskDefMap.get(rootTaskId);

            TaskRun rootTaskRun = taskRunRepository.findByWorkflowRunIdAndTaskId(run.getId(), rootTaskId);
            if (rootTaskRun != null) {
                rootTaskRun.setStatus(TaskRun.TaskStatus.RUNNING);
                taskRunRepository.save(rootTaskRun);
            }

            String taskType = TaskType.fromString(defTask != null ? defTask.getTaskType() : null).name();

            redisPublisher.publishTask(
                    run.getId(),
                    rootTaskId,
                    taskType,
                    defTask == null ? null : defTask.getCommand(),
                    defTask == null ? null : defTask.getName(),
                    defTask == null ? null : defTask.getTimeoutSeconds());
            log.info("Enqueued root task {} (type={}) for run {}", rootTaskId, taskType, run.getId());
        }

        return run.getId();
    }

    @Transactional
    public void onTaskCompleted(String workflowRunId, String taskId, boolean success, String lastError) {
        log.info("Task completed callback: run={} task={} success={} error={}", workflowRunId, taskId, success,
                lastError);

        Optional<TaskRun> trOpt = taskRunRepository.findByWorkflowRunIdAndTaskIdWithLock(workflowRunId, taskId);
        if (trOpt.isEmpty()) {
            log.warn("TaskRun not found for run={}, task={}", workflowRunId, taskId);
            return;
        }
        TaskRun tr = trOpt.get();

        if (tr.getStatus() == TaskRun.TaskStatus.SUCCESS ||
                tr.getStatus() == TaskRun.TaskStatus.FAILED) {
            log.warn("Task {} already completed with status {}, ignoring duplicate callback", taskId, tr.getStatus());
            return;
        }

        if (tr.getStatus() != TaskRun.TaskStatus.RUNNING) {
            log.warn("Task {} has unexpected status {} (expected RUNNING), processing anyway", taskId, tr.getStatus());
        }

        if (success) {
            tr.setStatus(TaskRun.TaskStatus.SUCCESS);
            tr.setFinishedAt(java.time.LocalDateTime.now());
            taskRunRepository.save(tr);

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    Set<String> readyTaskIds = dependencyTracker.onTaskCompleted(workflowRunId, taskId);
                    log.info("Task {} completion triggered {} ready task(s): {}", taskId, readyTaskIds.size(),
                            readyTaskIds);

                    for (String readyTaskId : readyTaskIds) {
                        TaskRun childTr = taskRunRepository.findByWorkflowRunIdAndTaskId(workflowRunId, readyTaskId);

                        if (childTr == null) {
                            log.warn("Ready task {} not found in database", readyTaskId);
                            continue;
                        }

                        if (childTr.getStatus() != TaskRun.TaskStatus.PENDING) {
                            log.warn("Ready task {} has unexpected status: {} (expected PENDING)", readyTaskId,
                                    childTr.getStatus());
                            continue;
                        }

                        childTr.setStatus(TaskRun.TaskStatus.RUNNING);
                        taskRunRepository.save(childTr);

                        redisPublisher.publishTask(
                                workflowRunId,
                                readyTaskId,
                                childTr.getTaskType(),
                                childTr.getCommand(),
                                childTr.getTaskName(),
                                childTr.getTimeoutSeconds());
                        log.info("Enqueued ready task {} (type={}, timeout={}s) for run {}", readyTaskId,
                                childTr.getTaskType(),
                                childTr.getTimeoutSeconds(), workflowRunId);
                    }

                    long incompleteCount = taskRunRepository.countByWorkflowRunIdAndStatusNot(workflowRunId,
                            TaskRun.TaskStatus.SUCCESS);
                    boolean allDone = (incompleteCount == 0);

                    if (allDone) {
                        WorkflowRun run = workflowRunRepository.findById(workflowRunId).orElse(null);
                        if (run != null) {
                            run.setStatus(WorkflowRun.RunStatus.COMPLETED);
                            run.setFinishedAt(java.time.LocalDateTime.now());
                            workflowRunRepository.save(run);
                            log.info("WorkflowRun {} COMPLETED successfully", workflowRunId);

                            redisDagCache.deleteDag(workflowRunId);
                            dependencyTracker.cleanup(workflowRunId);
                        }
                    }
                }
            });

        } else {
            tr.setRetryCount(tr.getRetryCount() + 1);
            tr.setLastError(lastError);
            taskRunRepository.save(tr);

            if (tr.getRetryCount() <= tr.getMaxRetries()) {
                log.info("Retrying task {} (type={}, timeout={}s) for run {} (attempt {}/{})",
                        taskId, tr.getTaskType(), tr.getTimeoutSeconds(), workflowRunId, tr.getRetryCount(),
                        tr.getMaxRetries());

                redisPublisher.publishTask(
                        workflowRunId,
                        taskId,
                        tr.getTaskType(),
                        tr.getCommand(),
                        tr.getTaskName(),
                        tr.getTimeoutSeconds());
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
                log.warn("Task {} permanently failed after {} retries. Workflow {} marked FAILED.", taskId,
                        tr.getRetryCount(), workflowRunId);

                redisDagCache.deleteDag(workflowRunId);
                dependencyTracker.cleanup(workflowRunId);
            }
        }
    }
}
