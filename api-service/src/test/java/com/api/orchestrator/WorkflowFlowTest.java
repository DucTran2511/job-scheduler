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
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedAcyclicGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Workflow Flow Tests")
class WorkflowFlowTest {

    @Mock
    private DagParser dagParser;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private TaskRunRepository taskRunRepository;

    @Mock
    private RedisPublisher redisPublisher;

    @Mock
    private RedisDagStore redisDagCache;

    @Mock
    private RedisDependencyTracker dependencyTracker;

    @InjectMocks
    private WorkflowOrchestrator orchestrator;

    // ========================================================================
    // Test Data Builders
    // ========================================================================

    private DagDefinition createSimpleWorkflow() {
        DagDefinition def = new DagDefinition();
        def.setName("simple-workflow");
        def.setDescription("Simple single task workflow");

        TaskDef task = new TaskDef();
        task.setId("task1");
        task.setName("Single Task");
        task.setCommand("echo hello");
        task.setTaskType("SHELL");
        task.setMaxRetries(3);

        def.setTasks(List.of(task));
        return def;
    }

    private DagDefinition createLinearWorkflow() {
        // task1 -> task2 -> task3 (linear chain)
        DagDefinition def = new DagDefinition();
        def.setName("linear-workflow");

        TaskDef task1 = new TaskDef();
        task1.setId("task1");
        task1.setName("First");
        task1.setCommand("echo 1");

        TaskDef task2 = new TaskDef();
        task2.setId("task2");
        task2.setName("Second");
        task2.setCommand("echo 2");
        task2.setDepends_on(List.of("task1"));

        TaskDef task3 = new TaskDef();
        task3.setId("task3");
        task3.setName("Third");
        task3.setCommand("echo 3");
        task3.setDepends_on(List.of("task2"));

        def.setTasks(List.of(task1, task2, task3));
        return def;
    }

    private DagDefinition createParallelWorkflow() {
        // task1, task2 (parallel roots) -> task3 (depends on both)
        DagDefinition def = new DagDefinition();
        def.setName("parallel-workflow");

        TaskDef task1 = new TaskDef();
        task1.setId("task1");
        task1.setCommand("echo 1");

        TaskDef task2 = new TaskDef();
        task2.setId("task2");
        task2.setCommand("echo 2");

        TaskDef task3 = new TaskDef();
        task3.setId("task3");
        task3.setCommand("echo 3");
        task3.setDepends_on(List.of("task1", "task2"));

        def.setTasks(List.of(task1, task2, task3));
        return def;
    }

    private DagDefinition createDiamondWorkflow() {
        //      task1
        //      /    \
        //   task2  task3
        //      \    /
        //      task4
        DagDefinition def = new DagDefinition();
        def.setName("diamond-workflow");

        TaskDef task1 = new TaskDef();
        task1.setId("task1");
        task1.setCommand("echo 1");

        TaskDef task2 = new TaskDef();
        task2.setId("task2");
        task2.setCommand("echo 2");
        task2.setDepends_on(List.of("task1"));

        TaskDef task3 = new TaskDef();
        task3.setId("task3");
        task3.setCommand("echo 3");
        task3.setDepends_on(List.of("task1"));

        TaskDef task4 = new TaskDef();
        task4.setId("task4");
        task4.setCommand("echo 4");
        task4.setDepends_on(List.of("task2", "task3"));

        def.setTasks(List.of(task1, task2, task3, task4));
        return def;
    }

    private DirectedAcyclicGraph<String, DefaultEdge> buildGraph(DagDefinition def) {
        DirectedAcyclicGraph<String, DefaultEdge> graph = new DirectedAcyclicGraph<>(DefaultEdge.class);
        for (TaskDef task : def.getTasks()) {
            graph.addVertex(task.getId());
        }
        for (TaskDef task : def.getTasks()) {
            if (task.getDepends_on() != null) {
                for (String dep : task.getDepends_on()) {
                    graph.addEdge(dep, task.getId());
                }
            }
        }
        return graph;
    }

    private WorkflowRun createWorkflowRun(String id) {
        WorkflowRun run = new WorkflowRun();
        run.setId(id);
        run.setStatus(WorkflowRun.RunStatus.RUNNING);
        return run;
    }

    private TaskRun createTaskRun(String workflowRunId, String taskId, TaskRun.TaskStatus status) {
        TaskRun tr = new TaskRun();
        tr.setId(UUID.randomUUID().toString());
        tr.setTaskId(taskId);
        tr.setStatus(status);
        tr.setCommand("echo " + taskId);
        tr.setTaskType("SHELL");
        tr.setRetryCount(0);
        tr.setMaxRetries(3);
        WorkflowRun run = new WorkflowRun();
        run.setId(workflowRunId);
        tr.setWorkflowRun(run);
        return tr;
    }

    // ========================================================================
    // Workflow Start Tests
    // ========================================================================

    @Nested
    @DisplayName("Workflow Start")
    class WorkflowStartTests {

        @Test
        @DisplayName("Should start simple single-task workflow")
        void shouldStartSimpleWorkflow() throws Exception {
            // Given
            DagDefinition def = createSimpleWorkflow();
            DirectedAcyclicGraph<String, DefaultEdge> graph = buildGraph(def);
            String yaml = "name: simple-workflow";

            when(dagParser.parseDefinition(yaml)).thenReturn(def);
            when(dagParser.buildGraph(def)).thenReturn(graph);
            when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(workflowRunRepository.save(any())).thenAnswer(inv -> {
                WorkflowRun run = inv.getArgument(0);
                if (run.getId() == null) run.setId("run-123");
                return run;
            });
            when(taskRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(taskRunRepository.findByWorkflowRunIdAndTaskId(anyString(), eq("task1")))
                .thenReturn(createTaskRun("run-123", "task1", TaskRun.TaskStatus.PENDING));
            when(redisDagCache.loadDag(anyString())).thenReturn(Map.of("task1", List.of()));

            // When
            String runId = orchestrator.startWorkflow(yaml);

            // Then
            assertThat(runId).isNotNull();
            verify(workflowRepository).save(any(WorkflowEntity.class));
            verify(workflowRunRepository).save(any(WorkflowRun.class));
            verify(taskRunRepository).save(any(TaskRun.class));
            verify(redisDagCache).saveDag(anyString(), eq(def));
            verify(redisPublisher).publishTask(anyString(), eq("task1"), eq("SHELL"), anyString(), any(), any());
        }

        @Test
        @DisplayName("Should start workflow with multiple root tasks in parallel")
        void shouldStartParallelRootTasks() throws Exception {
            // Given
            DagDefinition def = createParallelWorkflow();
            DirectedAcyclicGraph<String, DefaultEdge> graph = buildGraph(def);
            String yaml = "name: parallel-workflow";

            when(dagParser.parseDefinition(yaml)).thenReturn(def);
            when(dagParser.buildGraph(def)).thenReturn(graph);
            when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(workflowRunRepository.save(any())).thenAnswer(inv -> {
                WorkflowRun run = inv.getArgument(0);
                if (run.getId() == null) run.setId("run-456");
                return run;
            });
            when(taskRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(taskRunRepository.findByWorkflowRunIdAndTaskId(anyString(), eq("task1")))
                .thenReturn(createTaskRun("run-456", "task1", TaskRun.TaskStatus.PENDING));
            when(taskRunRepository.findByWorkflowRunIdAndTaskId(anyString(), eq("task2")))
                .thenReturn(createTaskRun("run-456", "task2", TaskRun.TaskStatus.PENDING));
            when(redisDagCache.loadDag(anyString())).thenReturn(Map.of(
                "task1", List.of(),
                "task2", List.of(),
                "task3", List.of("task1", "task2")
            ));

            // When
            String runId = orchestrator.startWorkflow(yaml);

            // Then
            assertThat(runId).isNotNull();

            // Both root tasks should be published
            verify(redisPublisher).publishTask(anyString(), eq("task1"), eq("SHELL"), anyString(), any(), any());
            verify(redisPublisher).publishTask(anyString(), eq("task2"), eq("SHELL"), anyString(), any(), any());

            // task3 should NOT be published (has dependencies)
            verify(redisPublisher, never()).publishTask(anyString(), eq("task3"), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should throw exception for invalid YAML")
        void shouldThrowExceptionForInvalidYaml() throws Exception {
            // Given
            String invalidYaml = "invalid: yaml: content";
            when(dagParser.parseDefinition(invalidYaml)).thenThrow(new RuntimeException("Invalid YAML"));

            // When/Then
            assertThatThrownBy(() -> orchestrator.startWorkflow(invalidYaml))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid YAML");
        }

        @Test
        @DisplayName("Should initialize dependency tracker for DAG")
        void shouldInitializeDependencyTracker() throws Exception {
            // Given
            DagDefinition def = createLinearWorkflow();
            DirectedAcyclicGraph<String, DefaultEdge> graph = buildGraph(def);
            String yaml = "name: linear-workflow";
            Map<String, List<String>> dagMap = Map.of(
                "task1", List.of(),
                "task2", List.of("task1"),
                "task3", List.of("task2")
            );

            when(dagParser.parseDefinition(yaml)).thenReturn(def);
            when(dagParser.buildGraph(def)).thenReturn(graph);
            when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(workflowRunRepository.save(any())).thenAnswer(inv -> {
                WorkflowRun run = inv.getArgument(0);
                if (run.getId() == null) run.setId("run-789");
                return run;
            });
            when(taskRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(taskRunRepository.findByWorkflowRunIdAndTaskId(anyString(), eq("task1")))
                .thenReturn(createTaskRun("run-789", "task1", TaskRun.TaskStatus.PENDING));
            when(redisDagCache.loadDag(anyString())).thenReturn(dagMap);

            // When
            orchestrator.startWorkflow(yaml);

            // Then
            verify(dependencyTracker).initializeDependencies(eq("run-789"), eq(dagMap));
        }
    }

    // ========================================================================
    // Task Completion Tests
    // ========================================================================

    @Nested
    @DisplayName("Task Completion - Success")
    class TaskCompletionSuccessTests {

        @Test
        @DisplayName("Should mark task as SUCCESS when completed successfully")
        void shouldMarkTaskAsSuccess() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.RUNNING);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);
            when(dependencyTracker.onTaskCompleted(runId, taskId)).thenReturn(Set.of());
            when(taskRunRepository.countByWorkflowRunIdAndStatusNot(runId, TaskRun.TaskStatus.SUCCESS)).thenReturn(1L);

            // When
            orchestrator.onTaskCompleted(runId, taskId, true, null);

            // Then
            assertThat(taskRun.getStatus()).isEqualTo(TaskRun.TaskStatus.SUCCESS);
            assertThat(taskRun.getFinishedAt()).isNotNull();
            verify(taskRunRepository).save(taskRun);
        }

        @Test
        @DisplayName("Should schedule dependent task when parent completes")
        void shouldScheduleDependentTask() {
            // Given
            String runId = "run-123";
            String parentTaskId = "task1";
            String childTaskId = "task2";

            TaskRun parentTask = createTaskRun(runId, parentTaskId, TaskRun.TaskStatus.RUNNING);
            TaskRun childTask = createTaskRun(runId, childTaskId, TaskRun.TaskStatus.PENDING);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, parentTaskId)).thenReturn(parentTask);
            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, childTaskId)).thenReturn(childTask);
            when(dependencyTracker.onTaskCompleted(runId, parentTaskId)).thenReturn(Set.of(childTaskId));
            when(taskRunRepository.countByWorkflowRunIdAndStatusNot(runId, TaskRun.TaskStatus.SUCCESS)).thenReturn(1L);

            // When
            orchestrator.onTaskCompleted(runId, parentTaskId, true, null);

            // Then
            assertThat(childTask.getStatus()).isEqualTo(TaskRun.TaskStatus.RUNNING);
            verify(redisPublisher).publishTask(eq(runId), eq(childTaskId), eq("SHELL"), anyString(), any(), any());
        }

        @Test
        @DisplayName("Should complete workflow when all tasks succeed")
        void shouldCompleteWorkflowWhenAllTasksSucceed() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.RUNNING);
            WorkflowRun workflowRun = createWorkflowRun(runId);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);
            when(dependencyTracker.onTaskCompleted(runId, taskId)).thenReturn(Set.of());
            when(taskRunRepository.countByWorkflowRunIdAndStatusNot(runId, TaskRun.TaskStatus.SUCCESS)).thenReturn(0L);
            when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(workflowRun));

            // When
            orchestrator.onTaskCompleted(runId, taskId, true, null);

            // Then
            assertThat(workflowRun.getStatus()).isEqualTo(WorkflowRun.RunStatus.COMPLETED);
            assertThat(workflowRun.getFinishedAt()).isNotNull();
            verify(workflowRunRepository).save(workflowRun);
            verify(redisDagCache).deleteDag(runId);
            verify(dependencyTracker).cleanup(runId);
        }

        @Test
        @DisplayName("Should handle multiple ready tasks from diamond pattern")
        void shouldHandleMultipleReadyTasks() {
            // Given (diamond: task1 -> task2, task3 -> task4)
            String runId = "run-123";
            String completedTaskId = "task1";

            TaskRun task1 = createTaskRun(runId, "task1", TaskRun.TaskStatus.RUNNING);
            TaskRun task2 = createTaskRun(runId, "task2", TaskRun.TaskStatus.PENDING);
            TaskRun task3 = createTaskRun(runId, "task3", TaskRun.TaskStatus.PENDING);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, "task1")).thenReturn(task1);
            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, "task2")).thenReturn(task2);
            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, "task3")).thenReturn(task3);
            when(dependencyTracker.onTaskCompleted(runId, completedTaskId)).thenReturn(Set.of("task2", "task3"));
            when(taskRunRepository.countByWorkflowRunIdAndStatusNot(runId, TaskRun.TaskStatus.SUCCESS)).thenReturn(3L);

            // When
            orchestrator.onTaskCompleted(runId, completedTaskId, true, null);

            // Then - both task2 and task3 should be scheduled
            assertThat(task2.getStatus()).isEqualTo(TaskRun.TaskStatus.RUNNING);
            assertThat(task3.getStatus()).isEqualTo(TaskRun.TaskStatus.RUNNING);
            verify(redisPublisher).publishTask(eq(runId), eq("task2"), eq("SHELL"), anyString(), any(), any());
            verify(redisPublisher).publishTask(eq(runId), eq("task3"), eq("SHELL"), anyString(), any(), any());
        }
    }

    // ========================================================================
    // Task Completion Tests - Failure & Retry
    // ========================================================================

    @Nested
    @DisplayName("Task Completion - Failure & Retry")
    class TaskCompletionFailureTests {

        @Test
        @DisplayName("Should retry failed task when retries remaining")
        void shouldRetryFailedTask() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.RUNNING);
            taskRun.setRetryCount(0);
            taskRun.setMaxRetries(3);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);

            // When
            orchestrator.onTaskCompleted(runId, taskId, false, "Connection timeout");

            // Then
            assertThat(taskRun.getRetryCount()).isEqualTo(1);
            assertThat(taskRun.getLastError()).isEqualTo("Connection timeout");
            assertThat(taskRun.getStatus()).isEqualTo(TaskRun.TaskStatus.RUNNING); // Still running for retry
            verify(redisPublisher).publishTask(eq(runId), eq(taskId), eq("SHELL"), anyString(), any(), any());
        }

        @Test
        @DisplayName("Should mark task as FAILED after max retries exceeded")
        void shouldFailTaskAfterMaxRetries() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.RUNNING);
            taskRun.setRetryCount(3);
            taskRun.setMaxRetries(3);
            WorkflowRun workflowRun = createWorkflowRun(runId);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);
            when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(workflowRun));

            // When
            orchestrator.onTaskCompleted(runId, taskId, false, "Permanent failure");

            // Then
            assertThat(taskRun.getStatus()).isEqualTo(TaskRun.TaskStatus.FAILED);
            assertThat(taskRun.getFinishedAt()).isNotNull();
            assertThat(workflowRun.getStatus()).isEqualTo(WorkflowRun.RunStatus.FAILED);
            verify(redisPublisher, never()).publishTask(anyString(), anyString(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should fail workflow when task permanently fails")
        void shouldFailWorkflowWhenTaskFails() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.RUNNING);
            taskRun.setRetryCount(3);
            taskRun.setMaxRetries(3);
            WorkflowRun workflowRun = createWorkflowRun(runId);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);
            when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(workflowRun));

            // When
            orchestrator.onTaskCompleted(runId, taskId, false, "Fatal error");

            // Then
            assertThat(workflowRun.getStatus()).isEqualTo(WorkflowRun.RunStatus.FAILED);
            assertThat(workflowRun.getFinishedAt()).isNotNull();
            verify(workflowRunRepository).save(workflowRun);
            verify(redisDagCache).deleteDag(runId);
            verify(dependencyTracker).cleanup(runId);
        }

        @Test
        @DisplayName("Should preserve error message across retries")
        void shouldPreserveErrorMessage() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.RUNNING);
            taskRun.setRetryCount(1);
            taskRun.setMaxRetries(3);
            taskRun.setLastError("Previous error");

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);

            // When
            orchestrator.onTaskCompleted(runId, taskId, false, "New error");

            // Then
            assertThat(taskRun.getLastError()).isEqualTo("New error");
            assertThat(taskRun.getRetryCount()).isEqualTo(2);
        }
    }

    // ========================================================================
    // Idempotency Tests
    // ========================================================================

    @Nested
    @DisplayName("Idempotency")
    class IdempotencyTests {

        @Test
        @DisplayName("Should ignore duplicate success callback")
        void shouldIgnoreDuplicateSuccessCallback() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.SUCCESS);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);

            // When
            orchestrator.onTaskCompleted(runId, taskId, true, null);

            // Then - should not process again
            verify(taskRunRepository, never()).save(any());
            verify(dependencyTracker, never()).onTaskCompleted(anyString(), anyString());
        }

        @Test
        @DisplayName("Should ignore duplicate failure callback")
        void shouldIgnoreDuplicateFailureCallback() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.FAILED);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);

            // When
            orchestrator.onTaskCompleted(runId, taskId, false, "error");

            // Then - should not process again
            verify(taskRunRepository, never()).save(any());
            verify(redisPublisher, never()).publishTask(anyString(), anyString(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should handle callback for non-existent task gracefully")
        void shouldHandleNonExistentTask() {
            // Given
            String runId = "run-123";
            String taskId = "non-existent";

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(null);

            // When - should not throw
            orchestrator.onTaskCompleted(runId, taskId, true, null);

            // Then - no action taken
            verify(taskRunRepository, never()).save(any());
            verify(dependencyTracker, never()).onTaskCompleted(anyString(), anyString());
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle task with null command")
        void shouldHandleTaskWithNullCommand() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.RUNNING);
            taskRun.setCommand(null);
            TaskRun childTask = createTaskRun(runId, "task2", TaskRun.TaskStatus.PENDING);
            childTask.setCommand(null);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);
            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, "task2")).thenReturn(childTask);
            when(dependencyTracker.onTaskCompleted(runId, taskId)).thenReturn(Set.of("task2"));
            when(taskRunRepository.countByWorkflowRunIdAndStatusNot(runId, TaskRun.TaskStatus.SUCCESS)).thenReturn(1L);

            // When - should not throw
            orchestrator.onTaskCompleted(runId, taskId, true, null);

            // Then
            verify(redisPublisher).publishTask(eq(runId), eq("task2"), eq("SHELL"), isNull(), any(), any());
        }

        @Test
        @DisplayName("Should skip ready task that is not PENDING")
        void shouldSkipNonPendingReadyTask() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            TaskRun task1 = createTaskRun(runId, "task1", TaskRun.TaskStatus.RUNNING);
            TaskRun task2 = createTaskRun(runId, "task2", TaskRun.TaskStatus.RUNNING); // Already running!

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, "task1")).thenReturn(task1);
            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, "task2")).thenReturn(task2);
            when(dependencyTracker.onTaskCompleted(runId, taskId)).thenReturn(Set.of("task2"));
            when(taskRunRepository.countByWorkflowRunIdAndStatusNot(runId, TaskRun.TaskStatus.SUCCESS)).thenReturn(1L);

            // When
            orchestrator.onTaskCompleted(runId, taskId, true, null);

            // Then - task2 should NOT be published again
            verify(redisPublisher, never()).publishTask(eq(runId), eq("task2"), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should handle empty ready tasks set")
        void shouldHandleEmptyReadyTasks() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.RUNNING);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);
            when(dependencyTracker.onTaskCompleted(runId, taskId)).thenReturn(Set.of());
            when(taskRunRepository.countByWorkflowRunIdAndStatusNot(runId, TaskRun.TaskStatus.SUCCESS)).thenReturn(2L);

            // When
            orchestrator.onTaskCompleted(runId, taskId, true, null);

            // Then - no tasks published
            verify(redisPublisher, never()).publishTask(anyString(), anyString(), any(), any(), any(), any());
        }
    }
}

