package com.api.orchestrator;

import com.api.cache.RedisDagStore;
import com.api.cache.RedisDependencyTracker;
import com.api.entity.TaskRun;
import com.api.entity.WorkflowRun;
import com.api.messaging.RedisPublisher;
import com.api.orchestrator.parser.DagParser;
import com.api.repository.TaskRunRepository;
import com.api.repository.WorkflowRepository;
import com.api.repository.WorkflowRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Task Execution Tests")
class TaskExecutionTest {

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
    // Helper Methods
    // ========================================================================

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

    private WorkflowRun createWorkflowRun(String id) {
        WorkflowRun run = new WorkflowRun();
        run.setId(id);
        run.setStatus(WorkflowRun.RunStatus.RUNNING);
        return run;
    }

    // ========================================================================
    // Task Status Transition Tests
    // ========================================================================

    @Nested
    @DisplayName("Task Status Transitions")
    class TaskStatusTransitionTests {

        @Test
        @DisplayName("RUNNING -> SUCCESS on successful completion")
        void shouldTransitionToSuccessOnCompletion() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.RUNNING);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);
            when(dependencyTracker.onTaskCompleted(runId, taskId)).thenReturn(Set.of());
            when(taskRunRepository.countByWorkflowRunIdAndStatusNot(runId, TaskRun.TaskStatus.SUCCESS)).thenReturn(0L);
            when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(createWorkflowRun(runId)));

            // When
            orchestrator.onTaskCompleted(runId, taskId, true, null);

            // Then
            assertThat(taskRun.getStatus()).isEqualTo(TaskRun.TaskStatus.SUCCESS);
        }

        @Test
        @DisplayName("RUNNING -> FAILED after max retries")
        void shouldTransitionToFailedAfterMaxRetries() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.RUNNING);
            taskRun.setRetryCount(3);
            taskRun.setMaxRetries(3);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);
            when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(createWorkflowRun(runId)));

            // When
            orchestrator.onTaskCompleted(runId, taskId, false, "Error");

            // Then
            assertThat(taskRun.getStatus()).isEqualTo(TaskRun.TaskStatus.FAILED);
        }

        @Test
        @DisplayName("RUNNING stays RUNNING during retry")
        void shouldStayRunningDuringRetry() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.RUNNING);
            taskRun.setRetryCount(0);
            taskRun.setMaxRetries(3);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);

            // When
            orchestrator.onTaskCompleted(runId, taskId, false, "Temporary error");

            // Then - status unchanged (still RUNNING for retry)
            assertThat(taskRun.getStatus()).isEqualTo(TaskRun.TaskStatus.RUNNING);
        }

        @Test
        @DisplayName("PENDING -> RUNNING when scheduled")
        void shouldTransitionPendingToRunningWhenScheduled() {
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
        }
    }

    // ========================================================================
    // Task Type Tests
    // ========================================================================

    @Nested
    @DisplayName("Task Types")
    class TaskTypeTests {

        @ParameterizedTest
        @ValueSource(strings = {"SHELL", "HTTP", "PYTHON", "DOCKER"})
        @DisplayName("Should preserve task type when scheduling dependent task")
        void shouldPreserveTaskType(String taskType) {
            // Given
            String runId = "run-123";
            String parentTaskId = "task1";
            String childTaskId = "task2";

            TaskRun parentTask = createTaskRun(runId, parentTaskId, TaskRun.TaskStatus.RUNNING);
            TaskRun childTask = createTaskRun(runId, childTaskId, TaskRun.TaskStatus.PENDING);
            childTask.setTaskType(taskType);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, parentTaskId)).thenReturn(parentTask);
            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, childTaskId)).thenReturn(childTask);
            when(dependencyTracker.onTaskCompleted(runId, parentTaskId)).thenReturn(Set.of(childTaskId));
            when(taskRunRepository.countByWorkflowRunIdAndStatusNot(runId, TaskRun.TaskStatus.SUCCESS)).thenReturn(1L);

            // When
            orchestrator.onTaskCompleted(runId, parentTaskId, true, null);

            // Then
            verify(redisPublisher).publishTask(
                eq(runId),
                eq(childTaskId),
                eq(taskType),
                anyString(),
                any(),
                any()
            );
        }

        @Test
        @DisplayName("Should default to SHELL type when null")
        void shouldDefaultToShellType() {
            // Given
            String runId = "run-123";
            String parentTaskId = "task1";
            String childTaskId = "task2";

            TaskRun parentTask = createTaskRun(runId, parentTaskId, TaskRun.TaskStatus.RUNNING);
            TaskRun childTask = createTaskRun(runId, childTaskId, TaskRun.TaskStatus.PENDING);
            childTask.setTaskType(null);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, parentTaskId)).thenReturn(parentTask);
            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, childTaskId)).thenReturn(childTask);
            when(dependencyTracker.onTaskCompleted(runId, parentTaskId)).thenReturn(Set.of(childTaskId));
            when(taskRunRepository.countByWorkflowRunIdAndStatusNot(runId, TaskRun.TaskStatus.SUCCESS)).thenReturn(1L);

            // When
            orchestrator.onTaskCompleted(runId, parentTaskId, true, null);

            // Then - should publish with null type (worker handles default)
            verify(redisPublisher).publishTask(
                eq(runId),
                eq(childTaskId),
                isNull(),
                anyString(),
                any(),
                any()
            );
        }
    }

    // ========================================================================
    // Timeout Tests
    // ========================================================================

    @Nested
    @DisplayName("Task Timeout")
    class TaskTimeoutTests {

        @Test
        @DisplayName("Should pass timeout to publisher when scheduling task")
        void shouldPassTimeoutToPublisher() {
            // Given
            String runId = "run-123";
            String parentTaskId = "task1";
            String childTaskId = "task2";
            Integer timeout = 300; // 5 minutes

            TaskRun parentTask = createTaskRun(runId, parentTaskId, TaskRun.TaskStatus.RUNNING);
            TaskRun childTask = createTaskRun(runId, childTaskId, TaskRun.TaskStatus.PENDING);
            childTask.setTimeoutSeconds(timeout);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, parentTaskId)).thenReturn(parentTask);
            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, childTaskId)).thenReturn(childTask);
            when(dependencyTracker.onTaskCompleted(runId, parentTaskId)).thenReturn(Set.of(childTaskId));
            when(taskRunRepository.countByWorkflowRunIdAndStatusNot(runId, TaskRun.TaskStatus.SUCCESS)).thenReturn(1L);

            // When
            orchestrator.onTaskCompleted(runId, parentTaskId, true, null);

            // Then
            verify(redisPublisher).publishTask(
                eq(runId),
                eq(childTaskId),
                anyString(),
                anyString(),
                any(),
                eq(timeout)
            );
        }

        @Test
        @DisplayName("Should handle null timeout (no timeout)")
        void shouldHandleNullTimeout() {
            // Given
            String runId = "run-123";
            String parentTaskId = "task1";
            String childTaskId = "task2";

            TaskRun parentTask = createTaskRun(runId, parentTaskId, TaskRun.TaskStatus.RUNNING);
            TaskRun childTask = createTaskRun(runId, childTaskId, TaskRun.TaskStatus.PENDING);
            childTask.setTimeoutSeconds(null);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, parentTaskId)).thenReturn(parentTask);
            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, childTaskId)).thenReturn(childTask);
            when(dependencyTracker.onTaskCompleted(runId, parentTaskId)).thenReturn(Set.of(childTaskId));
            when(taskRunRepository.countByWorkflowRunIdAndStatusNot(runId, TaskRun.TaskStatus.SUCCESS)).thenReturn(1L);

            // When
            orchestrator.onTaskCompleted(runId, parentTaskId, true, null);

            // Then
            verify(redisPublisher).publishTask(
                eq(runId),
                eq(childTaskId),
                anyString(),
                anyString(),
                any(),
                isNull()
            );
        }

        @ParameterizedTest
        @ValueSource(ints = {30, 60, 300, 3600})
        @DisplayName("Should preserve various timeout values")
        void shouldPreserveVariousTimeouts(int timeoutSeconds) {
            // Given
            String runId = "run-123";
            String parentTaskId = "task1";
            String childTaskId = "task2";

            TaskRun parentTask = createTaskRun(runId, parentTaskId, TaskRun.TaskStatus.RUNNING);
            TaskRun childTask = createTaskRun(runId, childTaskId, TaskRun.TaskStatus.PENDING);
            childTask.setTimeoutSeconds(timeoutSeconds);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, parentTaskId)).thenReturn(parentTask);
            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, childTaskId)).thenReturn(childTask);
            when(dependencyTracker.onTaskCompleted(runId, parentTaskId)).thenReturn(Set.of(childTaskId));
            when(taskRunRepository.countByWorkflowRunIdAndStatusNot(runId, TaskRun.TaskStatus.SUCCESS)).thenReturn(1L);

            // When
            orchestrator.onTaskCompleted(runId, parentTaskId, true, null);

            // Then
            verify(redisPublisher).publishTask(
                anyString(), anyString(), anyString(), anyString(), any(), eq(timeoutSeconds)
            );
        }
    }

    // ========================================================================
    // Retry Behavior Tests
    // ========================================================================

    @Nested
    @DisplayName("Task Retry Behavior")
    class TaskRetryBehaviorTests {

        @ParameterizedTest
        @CsvSource({
            "0, 3, true",   // retry 0 of 3 -> should retry
            "1, 3, true",   // retry 1 of 3 -> should retry
            "2, 3, true",   // retry 2 of 3 -> should retry
            "3, 3, false",  // retry 3 of 3 -> should NOT retry (exceeded)
            "0, 0, false",  // no retries allowed -> should NOT retry
            "1, 1, false",  // retry 1 of 1 -> should NOT retry (exceeded)
        })
        @DisplayName("Should correctly determine if retry is allowed")
        void shouldDetermineRetryCorrectly(int currentRetry, int maxRetries, boolean shouldRetry) {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.RUNNING);
            taskRun.setRetryCount(currentRetry);
            taskRun.setMaxRetries(maxRetries);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);
            if (!shouldRetry) {
                when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(createWorkflowRun(runId)));
            }

            // When
            orchestrator.onTaskCompleted(runId, taskId, false, "Error");

            // Then
            if (shouldRetry) {
                verify(redisPublisher).publishTask(anyString(), eq(taskId), anyString(), anyString(), any(), any());
                assertThat(taskRun.getStatus()).isNotEqualTo(TaskRun.TaskStatus.FAILED);
            } else {
                verify(redisPublisher, never()).publishTask(anyString(), anyString(), any(), any(), any(), any());
                assertThat(taskRun.getStatus()).isEqualTo(TaskRun.TaskStatus.FAILED);
            }
        }

        @Test
        @DisplayName("Should increment retry count on each failure")
        void shouldIncrementRetryCount() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.RUNNING);
            taskRun.setRetryCount(1);
            taskRun.setMaxRetries(5);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);

            // When
            orchestrator.onTaskCompleted(runId, taskId, false, "Error");

            // Then
            assertThat(taskRun.getRetryCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should pass same command on retry")
        void shouldPassSameCommandOnRetry() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            String command = "curl https://api.example.com/data";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.RUNNING);
            taskRun.setCommand(command);
            taskRun.setRetryCount(0);
            taskRun.setMaxRetries(3);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);

            // When
            orchestrator.onTaskCompleted(runId, taskId, false, "Timeout");

            // Then
            verify(redisPublisher).publishTask(
                eq(runId),
                eq(taskId),
                anyString(),
                eq(command),
                any(),
                any()
            );
        }
    }

    // ========================================================================
    // Error Handling Tests
    // ========================================================================

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should store error message on failure")
        void shouldStoreErrorMessage() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            String errorMessage = "Connection refused: localhost:5432";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.RUNNING);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);

            // When
            orchestrator.onTaskCompleted(runId, taskId, false, errorMessage);

            // Then
            assertThat(taskRun.getLastError()).isEqualTo(errorMessage);
        }

        @Test
        @DisplayName("Should handle null error message")
        void shouldHandleNullErrorMessage() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.RUNNING);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);

            // When - should not throw
            orchestrator.onTaskCompleted(runId, taskId, false, null);

            // Then
            assertThat(taskRun.getLastError()).isNull();
        }

        @Test
        @DisplayName("Should handle very long error message")
        void shouldHandleLongErrorMessage() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            String longError = "Error: " + "x".repeat(10000); // 10KB error message
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.RUNNING);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);

            // When - should not throw
            orchestrator.onTaskCompleted(runId, taskId, false, longError);

            // Then
            assertThat(taskRun.getLastError()).isEqualTo(longError);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "exit code 1",
            "java.lang.OutOfMemoryError",
            "Connection timeout after 30000ms",
            "Process killed by OOM killer",
            "bash: command not found"
        })
        @DisplayName("Should store various error types")
        void shouldStoreVariousErrorTypes(String errorMessage) {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.RUNNING);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);

            // When
            orchestrator.onTaskCompleted(runId, taskId, false, errorMessage);

            // Then
            assertThat(taskRun.getLastError()).isEqualTo(errorMessage);
        }
    }

    // ========================================================================
    // Task Metadata Tests
    // ========================================================================

    @Nested
    @DisplayName("Task Metadata")
    class TaskMetadataTests {

        @Test
        @DisplayName("Should preserve task name when scheduling")
        void shouldPreserveTaskName() {
            // Given
            String runId = "run-123";
            String parentTaskId = "task1";
            String childTaskId = "task2";
            String taskName = "Data Processing Task";

            TaskRun parentTask = createTaskRun(runId, parentTaskId, TaskRun.TaskStatus.RUNNING);
            TaskRun childTask = createTaskRun(runId, childTaskId, TaskRun.TaskStatus.PENDING);
            childTask.setTaskName(taskName);

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, parentTaskId)).thenReturn(parentTask);
            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, childTaskId)).thenReturn(childTask);
            when(dependencyTracker.onTaskCompleted(runId, parentTaskId)).thenReturn(Set.of(childTaskId));
            when(taskRunRepository.countByWorkflowRunIdAndStatusNot(runId, TaskRun.TaskStatus.SUCCESS)).thenReturn(1L);

            // When
            orchestrator.onTaskCompleted(runId, parentTaskId, true, null);

            // Then
            verify(redisPublisher).publishTask(
                eq(runId),
                eq(childTaskId),
                anyString(),
                anyString(),
                eq(taskName),
                any()
            );
        }

        @Test
        @DisplayName("Should set finishedAt timestamp on completion")
        void shouldSetFinishedAtOnCompletion() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.RUNNING);
            assertThat(taskRun.getFinishedAt()).isNull();

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);
            when(dependencyTracker.onTaskCompleted(runId, taskId)).thenReturn(Set.of());
            when(taskRunRepository.countByWorkflowRunIdAndStatusNot(runId, TaskRun.TaskStatus.SUCCESS)).thenReturn(0L);
            when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(createWorkflowRun(runId)));

            // When
            orchestrator.onTaskCompleted(runId, taskId, true, null);

            // Then
            assertThat(taskRun.getFinishedAt()).isNotNull();
        }

        @Test
        @DisplayName("Should set finishedAt timestamp on permanent failure")
        void shouldSetFinishedAtOnFailure() {
            // Given
            String runId = "run-123";
            String taskId = "task1";
            TaskRun taskRun = createTaskRun(runId, taskId, TaskRun.TaskStatus.RUNNING);
            taskRun.setRetryCount(3);
            taskRun.setMaxRetries(3);
            assertThat(taskRun.getFinishedAt()).isNull();

            when(taskRunRepository.findByWorkflowRunIdAndTaskId(runId, taskId)).thenReturn(taskRun);
            when(workflowRunRepository.findById(runId)).thenReturn(Optional.of(createWorkflowRun(runId)));

            // When
            orchestrator.onTaskCompleted(runId, taskId, false, "Fatal error");

            // Then
            assertThat(taskRun.getFinishedAt()).isNotNull();
        }
    }
}

