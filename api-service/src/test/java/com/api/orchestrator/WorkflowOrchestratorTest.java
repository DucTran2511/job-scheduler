package com.api.orchestrator;

import com.api.cache.RedisDagStore;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowOrchestratorTest {

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

    @InjectMocks
    private WorkflowOrchestrator orchestrator;

    private DagDefinition testDagDefinition;
    private DirectedAcyclicGraph<String, DefaultEdge> testDag;
    private WorkflowEntity testWorkflowEntity;
    private WorkflowRun testWorkflowRun;

    @BeforeEach
    void setUp() {
        // Setup test DAG definition
        testDagDefinition = new DagDefinition();
        testDagDefinition.setName("test-workflow");
        testDagDefinition.setDescription("Test workflow");

        TaskDef task1 = new TaskDef();
        task1.setId("task1");
        task1.setName("First Task");
        task1.setCommand("echo task1");
        task1.setMaxRetries(3);

        TaskDef task2 = new TaskDef();
        task2.setId("task2");
        task2.setName("Second Task");
        task2.setCommand("echo task2");
        task2.setDepends_on(Arrays.asList("task1"));

        testDagDefinition.setTasks(Arrays.asList(task1, task2));

        // Setup test DAG
        testDag = new DirectedAcyclicGraph<>(DefaultEdge.class);
        testDag.addVertex("task1");
        testDag.addVertex("task2");
        testDag.addEdge("task1", "task2");

        // Setup test entities
        testWorkflowEntity = new WorkflowEntity();
        testWorkflowEntity.setId("workflow-123");
        testWorkflowEntity.setName("test-workflow");

        testWorkflowRun = new WorkflowRun();
        testWorkflowRun.setId("run-123");
        testWorkflowRun.setWorkflow(testWorkflowEntity);
        testWorkflowRun.setStatus(WorkflowRun.RunStatus.RUNNING);
    }

    @Test
    void startWorkflow_ShouldCreateWorkflowRunAndEnqueueRootTasks() throws Exception {
        // Given
        String yaml = "name: test-workflow\ntasks:\n  - id: task1";

        when(dagParser.parseDefinition(yaml)).thenReturn(testDagDefinition);
        when(dagParser.buildGraph(testDagDefinition)).thenReturn(testDag);
        when(workflowRepository.save(any(WorkflowEntity.class))).thenReturn(testWorkflowEntity);
        when(workflowRunRepository.save(any(WorkflowRun.class))).thenReturn(testWorkflowRun);
        when(taskRunRepository.save(any(TaskRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        String runId = orchestrator.startWorkflow(yaml);

        // Then
        assertThat(runId).isEqualTo("run-123");

        // Verify workflow entity was saved
        ArgumentCaptor<WorkflowEntity> workflowCaptor = ArgumentCaptor.forClass(WorkflowEntity.class);
        verify(workflowRepository).save(workflowCaptor.capture());
        assertThat(workflowCaptor.getValue().getName()).isEqualTo("test-workflow");
        assertThat(workflowCaptor.getValue().getRawDefinition()).isEqualTo(yaml);

        // Verify workflow run was saved
        ArgumentCaptor<WorkflowRun> runCaptor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(workflowRunRepository).save(runCaptor.capture());
        assertThat(runCaptor.getValue().getStatus()).isEqualTo(WorkflowRun.RunStatus.RUNNING);

        // Verify task runs were created (2 tasks)
        verify(taskRunRepository, times(2)).save(any(TaskRun.class));

        // Verify DAG was cached
        verify(redisDagCache).saveDag(eq("run-123"), eq(testDagDefinition));

        // Verify root task (task1) was published
        verify(redisPublisher).publishTask(eq("run-123"), eq("task1"), eq("echo task1"));
    }

    @Test
    void startWorkflow_ShouldUseRandomNameWhenNameIsNull() throws Exception {
        // Given
        testDagDefinition.setName(null);
        String yaml = "tasks:\n  - id: task1";

        when(dagParser.parseDefinition(yaml)).thenReturn(testDagDefinition);
        when(dagParser.buildGraph(testDagDefinition)).thenReturn(testDag);
        when(workflowRepository.save(any(WorkflowEntity.class))).thenReturn(testWorkflowEntity);
        when(workflowRunRepository.save(any(WorkflowRun.class))).thenReturn(testWorkflowRun);
        when(taskRunRepository.save(any(TaskRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        orchestrator.startWorkflow(yaml);

        // Then
        ArgumentCaptor<WorkflowEntity> captor = ArgumentCaptor.forClass(WorkflowEntity.class);
        verify(workflowRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isNotNull();
        assertThat(captor.getValue().getName()).isNotEmpty();
    }

    @Test
    void startWorkflow_ShouldCreateTaskRunsWithCorrectProperties() throws Exception {
        // Given
        String yaml = "name: test";
        when(dagParser.parseDefinition(yaml)).thenReturn(testDagDefinition);
        when(dagParser.buildGraph(testDagDefinition)).thenReturn(testDag);
        when(workflowRepository.save(any(WorkflowEntity.class))).thenReturn(testWorkflowEntity);
        when(workflowRunRepository.save(any(WorkflowRun.class))).thenReturn(testWorkflowRun);
        when(taskRunRepository.save(any(TaskRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        orchestrator.startWorkflow(yaml);

        // Then
        ArgumentCaptor<TaskRun> captor = ArgumentCaptor.forClass(TaskRun.class);
        verify(taskRunRepository, times(2)).save(captor.capture());

        List<TaskRun> taskRuns = captor.getAllValues();
        TaskRun task1Run = taskRuns.stream().filter(tr -> "task1".equals(tr.getTaskId())).findFirst().orElseThrow();

        assertThat(task1Run.getTaskId()).isEqualTo("task1");
        assertThat(task1Run.getTaskName()).isEqualTo("First Task");
        assertThat(task1Run.getCommand()).isEqualTo("echo task1");
        assertThat(task1Run.getStatus()).isEqualTo(TaskRun.TaskStatus.PENDING);
        assertThat(task1Run.getRetryCount()).isEqualTo(0);
        assertThat(task1Run.getMaxRetries()).isEqualTo(3);
    }

    @Test
    void startWorkflow_ShouldThrowException_WhenParsingFails() throws Exception {
        // Given
        String invalidYaml = "invalid yaml {{";
        when(dagParser.parseDefinition(invalidYaml)).thenThrow(new RuntimeException("Invalid YAML"));

        // When & Then
        assertThatThrownBy(() -> orchestrator.startWorkflow(invalidYaml))
                .isInstanceOf(Exception.class)
                .hasMessageContaining("Invalid YAML");
    }

    @Test
    void onTaskCompleted_ShouldMarkTaskAsSuccess_WhenTaskSucceeds() {
        // Given
        TaskRun taskRun = createTaskRun("run-123", "task1", TaskRun.TaskStatus.RUNNING);

        when(taskRunRepository.findByWorkflowRunIdAndTaskId("run-123", "task1")).thenReturn(taskRun);
        when(taskRunRepository.save(any(TaskRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(redisDagCache.loadDag("run-123")).thenReturn(new HashMap<>());
        when(taskRunRepository.findByWorkflowRunId("run-123")).thenReturn(Arrays.asList(taskRun));

        // When
        orchestrator.onTaskCompleted("run-123", "task1", true, null);

        // Then
        ArgumentCaptor<TaskRun> captor = ArgumentCaptor.forClass(TaskRun.class);
        verify(taskRunRepository, atLeastOnce()).save(captor.capture());

        TaskRun savedTask = captor.getValue();
        assertThat(savedTask.getStatus()).isEqualTo(TaskRun.TaskStatus.SUCCESS);
        assertThat(savedTask.getFinishedAt()).isNotNull();
    }

    @Test
    void onTaskCompleted_ShouldEnqueueDependentTasks_WhenAllParentsSucceed() {
        // Given
        TaskRun task1 = createTaskRun("run-123", "task1", TaskRun.TaskStatus.SUCCESS);
        TaskRun task2 = createTaskRun("run-123", "task2", TaskRun.TaskStatus.PENDING);
        task2.setCommand("echo task2");

        Map<String, List<String>> dag = new HashMap<>();
        dag.put("task2", Arrays.asList("task1")); // task2 depends on task1

        when(taskRunRepository.findByWorkflowRunIdAndTaskId("run-123", "task1")).thenReturn(task1);
        when(taskRunRepository.findByWorkflowRunIdAndTaskId("run-123", "task2")).thenReturn(task2);
        when(taskRunRepository.save(any(TaskRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(redisDagCache.loadDag("run-123")).thenReturn(dag);
        when(taskRunRepository.findByWorkflowRunId("run-123")).thenReturn(Arrays.asList(task1, task2));

        // When
        orchestrator.onTaskCompleted("run-123", "task1", true, null);

        // Then
        verify(redisPublisher).publishTask("run-123", "task2", "echo task2");
    }

    @Test
    void onTaskCompleted_ShouldNotEnqueueDependentTask_WhenParentsFailed() {
        // Given
        TaskRun task1 = createTaskRun("run-123", "task1", TaskRun.TaskStatus.FAILED);
        TaskRun task2 = createTaskRun("run-123", "task2", TaskRun.TaskStatus.PENDING);

        Map<String, List<String>> dag = new HashMap<>();
        dag.put("task2", Arrays.asList("task1"));

        when(taskRunRepository.findByWorkflowRunIdAndTaskId("run-123", "task1")).thenReturn(task1);
        when(taskRunRepository.findByWorkflowRunIdAndTaskId("run-123", "task2")).thenReturn(task2);
        when(taskRunRepository.save(any(TaskRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(redisDagCache.loadDag("run-123")).thenReturn(dag);
        when(taskRunRepository.findByWorkflowRunId("run-123")).thenReturn(Arrays.asList(task1, task2));

        // When
        orchestrator.onTaskCompleted("run-123", "task1", true, null);

        // Then
        verify(redisPublisher, never()).publishTask(eq("run-123"), eq("task2"), anyString());
    }

    @Test
    void onTaskCompleted_ShouldMarkWorkflowAsCompleted_WhenAllTasksSucceed() {
        // Given
        TaskRun task1 = createTaskRun("run-123", "task1", TaskRun.TaskStatus.SUCCESS);
        TaskRun task2 = createTaskRun("run-123", "task2", TaskRun.TaskStatus.RUNNING);

        when(taskRunRepository.findByWorkflowRunIdAndTaskId("run-123", "task2")).thenReturn(task2);
        when(taskRunRepository.save(any(TaskRun.class))).thenAnswer(invocation -> {
            TaskRun tr = invocation.getArgument(0);
            tr.setStatus(TaskRun.TaskStatus.SUCCESS);
            return tr;
        });
        when(redisDagCache.loadDag("run-123")).thenReturn(new HashMap<>());
        when(taskRunRepository.findByWorkflowRunId("run-123")).thenReturn(Arrays.asList(task1, task2));
        when(workflowRunRepository.findById("run-123")).thenReturn(Optional.of(testWorkflowRun));

        // When
        orchestrator.onTaskCompleted("run-123", "task2", true, null);

        // Then
        ArgumentCaptor<WorkflowRun> captor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(workflowRunRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(WorkflowRun.RunStatus.COMPLETED);
        assertThat(captor.getValue().getFinishedAt()).isNotNull();
        verify(redisDagCache).deleteDag("run-123");
    }

    @Test
    void onTaskCompleted_ShouldRetryTask_WhenTaskFailsAndRetriesRemain() {
        // Given
        TaskRun taskRun = createTaskRun("run-123", "task1", TaskRun.TaskStatus.RUNNING);
        taskRun.setRetryCount(0);
        taskRun.setMaxRetries(3);
        taskRun.setCommand("echo retry");

        when(taskRunRepository.findByWorkflowRunIdAndTaskId("run-123", "task1")).thenReturn(taskRun);
        when(taskRunRepository.save(any(TaskRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        orchestrator.onTaskCompleted("run-123", "task1", false, "Connection timeout");

        // Then
        ArgumentCaptor<TaskRun> captor = ArgumentCaptor.forClass(TaskRun.class);
        verify(taskRunRepository, atLeastOnce()).save(captor.capture());

        TaskRun savedTask = captor.getValue();
        assertThat(savedTask.getRetryCount()).isEqualTo(1);
        assertThat(savedTask.getLastError()).isEqualTo("Connection timeout");

        verify(redisPublisher).publishTask("run-123", "task1", "echo retry");
    }

    @Test
    void onTaskCompleted_ShouldMarkTaskAndWorkflowAsFailed_WhenMaxRetriesExceeded() {
        // Given
        TaskRun taskRun = createTaskRun("run-123", "task1", TaskRun.TaskStatus.RUNNING);
        taskRun.setRetryCount(3);
        taskRun.setMaxRetries(3);

        when(taskRunRepository.findByWorkflowRunIdAndTaskId("run-123", "task1")).thenReturn(taskRun);
        when(taskRunRepository.save(any(TaskRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowRunRepository.findById("run-123")).thenReturn(Optional.of(testWorkflowRun));

        // When
        orchestrator.onTaskCompleted("run-123", "task1", false, "Permanent failure");

        // Then
        ArgumentCaptor<TaskRun> taskCaptor = ArgumentCaptor.forClass(TaskRun.class);
        verify(taskRunRepository, atLeastOnce()).save(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(TaskRun.TaskStatus.FAILED);
        assertThat(taskCaptor.getValue().getFinishedAt()).isNotNull();

        ArgumentCaptor<WorkflowRun> runCaptor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(workflowRunRepository).save(runCaptor.capture());
        assertThat(runCaptor.getValue().getStatus()).isEqualTo(WorkflowRun.RunStatus.FAILED);

        verify(redisDagCache).deleteDag("run-123");
        verify(redisPublisher, never()).publishTask(anyString(), anyString(), anyString());
    }

    @Test
    void onTaskCompleted_ShouldHandleNullTaskRun() {
        // Given
        when(taskRunRepository.findByWorkflowRunIdAndTaskId("run-123", "non-existent")).thenReturn(null);

        // When
        orchestrator.onTaskCompleted("run-123", "non-existent", true, null);

        // Then
        verify(taskRunRepository, never()).save(any(TaskRun.class));
        verify(redisPublisher, never()).publishTask(anyString(), anyString(), anyString());
    }

    @Test
    void onTaskCompleted_ShouldHandleNullDagFromCache() {
        // Given
        TaskRun taskRun = createTaskRun("run-123", "task1", TaskRun.TaskStatus.RUNNING);

        when(taskRunRepository.findByWorkflowRunIdAndTaskId("run-123", "task1")).thenReturn(taskRun);
        when(taskRunRepository.save(any(TaskRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(redisDagCache.loadDag("run-123")).thenReturn(null);

        // When
        orchestrator.onTaskCompleted("run-123", "task1", true, null);

        // Then
        verify(taskRunRepository).save(any(TaskRun.class));
        verify(redisPublisher, never()).publishTask(anyString(), anyString(), anyString());
    }

    private TaskRun createTaskRun(String workflowRunId, String taskId, TaskRun.TaskStatus status) {
        TaskRun taskRun = new TaskRun();
        taskRun.setId(UUID.randomUUID().toString());
        taskRun.setWorkflowRun(testWorkflowRun);
        taskRun.setTaskId(taskId);
        taskRun.setTaskName("Task " + taskId);
        taskRun.setCommand("echo " + taskId);
        taskRun.setStatus(status);
        taskRun.setRetryCount(0);
        taskRun.setMaxRetries(3);
        if (status == TaskRun.TaskStatus.SUCCESS) {
            taskRun.setStartedAt(LocalDateTime.now().minusMinutes(5));
            taskRun.setFinishedAt(LocalDateTime.now());
        }
        return taskRun;
    }
}
