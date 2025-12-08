package com.api.repository;

import com.api.entity.TaskRun;
import com.api.entity.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class TaskRunRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private TaskRunRepository taskRunRepository;

    private WorkflowRun workflowRun;

    @BeforeEach
    void setUp() {
        workflowRun = new WorkflowRun();
        workflowRun.setStatus(WorkflowRun.RunStatus.RUNNING);
        workflowRun.setStartedAt(LocalDateTime.now());
        entityManager.persist(workflowRun);
        entityManager.flush();
    }

    @Test
    void shouldSaveAndRetrieveTaskRun() {
        // Given
        TaskRun taskRun = new TaskRun();
        taskRun.setWorkflowRun(workflowRun);
        taskRun.setTaskId("task-1");
        taskRun.setTaskName("Download File");
        taskRun.setCommand("curl http://example.com");
        taskRun.setStatus(TaskRun.TaskStatus.PENDING);
        taskRun.setMaxRetries(3);

        // When
        TaskRun saved = taskRunRepository.save(taskRun);

        // Then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTaskId()).isEqualTo("task-1");
        assertThat(saved.getTaskName()).isEqualTo("Download File");
        assertThat(saved.getStatus()).isEqualTo(TaskRun.TaskStatus.PENDING);
    }

    @Test
    void shouldFindTaskRunByWorkflowRunIdAndTaskId() {
        // Given
        TaskRun taskRun = new TaskRun();
        taskRun.setWorkflowRun(workflowRun);
        taskRun.setTaskId("task-search");
        taskRun.setTaskName("Search Task");
        taskRun.setStatus(TaskRun.TaskStatus.RUNNING);
        entityManager.persist(taskRun);
        entityManager.flush();

        // When
        TaskRun found = taskRunRepository.findByWorkflowRunIdAndTaskId(
                workflowRun.getId(), "task-search");

        // Then
        assertThat(found).isNotNull();
        assertThat(found.getTaskId()).isEqualTo("task-search");
        assertThat(found.getWorkflowRun().getId()).isEqualTo(workflowRun.getId());
    }

    @Test
    void shouldFindAllTaskRunsByWorkflowRunId() {
        // Given
        TaskRun task1 = createTaskRun("task-1", "Task 1", TaskRun.TaskStatus.SUCCESS);
        TaskRun task2 = createTaskRun("task-2", "Task 2", TaskRun.TaskStatus.RUNNING);
        TaskRun task3 = createTaskRun("task-3", "Task 3", TaskRun.TaskStatus.PENDING);

        entityManager.persist(task1);
        entityManager.persist(task2);
        entityManager.persist(task3);
        entityManager.flush();

        // When
        List<TaskRun> taskRuns = taskRunRepository.findByWorkflowRunId(workflowRun.getId());

        // Then
        assertThat(taskRuns).hasSize(3);
        assertThat(taskRuns).extracting(TaskRun::getTaskId)
                .containsExactlyInAnyOrder("task-1", "task-2", "task-3");
    }

    @Test
    void shouldFindTaskRunsByTaskId() {
        // Given
        WorkflowRun anotherWorkflow = new WorkflowRun();
        anotherWorkflow.setStatus(WorkflowRun.RunStatus.RUNNING);
        entityManager.persist(anotherWorkflow);

        TaskRun task1 = createTaskRun("duplicate-task", "Task", TaskRun.TaskStatus.SUCCESS);
        TaskRun task2 = new TaskRun();
        task2.setWorkflowRun(anotherWorkflow);
        task2.setTaskId("duplicate-task");
        task2.setStatus(TaskRun.TaskStatus.FAILED);

        entityManager.persist(task1);
        entityManager.persist(task2);
        entityManager.flush();

        // When
        List<TaskRun> taskRuns = taskRunRepository.findByTaskId("duplicate-task");

        // Then
        assertThat(taskRuns).hasSize(2);
        assertThat(taskRuns).extracting(TaskRun::getTaskId)
                .containsOnly("duplicate-task");
    }

    @Test
    void shouldFindTaskRunsByStatus() {
        // Given
        TaskRun pending1 = createTaskRun("task-1", "Task 1", TaskRun.TaskStatus.PENDING);
        TaskRun pending2 = createTaskRun("task-2", "Task 2", TaskRun.TaskStatus.PENDING);
        TaskRun running = createTaskRun("task-3", "Task 3", TaskRun.TaskStatus.RUNNING);

        entityManager.persist(pending1);
        entityManager.persist(pending2);
        entityManager.persist(running);
        entityManager.flush();

        // When
        List<TaskRun> pendingTasks = taskRunRepository.findByStatus(TaskRun.TaskStatus.PENDING);

        // Then
        assertThat(pendingTasks).hasSize(2);
        assertThat(pendingTasks).extracting(TaskRun::getStatus)
                .containsOnly(TaskRun.TaskStatus.PENDING);
    }

    @Test
    void shouldUpdateTaskRunStatus() {
        // Given
        TaskRun taskRun = createTaskRun("task-update", "Update Task", TaskRun.TaskStatus.PENDING);
        entityManager.persist(taskRun);
        entityManager.flush();

        // When
        taskRun.setStatus(TaskRun.TaskStatus.RUNNING);
        taskRun.setStartedAt(LocalDateTime.now());
        TaskRun updated = taskRunRepository.save(taskRun);

        // Then
        assertThat(updated.getStatus()).isEqualTo(TaskRun.TaskStatus.RUNNING);
        assertThat(updated.getStartedAt()).isNotNull();
    }

    @Test
    void shouldIncrementRetryCount() {
        // Given
        TaskRun taskRun = createTaskRun("retry-task", "Retry Task", TaskRun.TaskStatus.FAILED);
        taskRun.setRetryCount(0);
        taskRun.setMaxRetries(3);
        entityManager.persist(taskRun);
        entityManager.flush();

        // When
        taskRun.setRetryCount(taskRun.getRetryCount() + 1);
        taskRun.setStatus(TaskRun.TaskStatus.PENDING);
        TaskRun updated = taskRunRepository.save(taskRun);

        // Then
        assertThat(updated.getRetryCount()).isEqualTo(1);
        assertThat(updated.getStatus()).isEqualTo(TaskRun.TaskStatus.PENDING);
    }

    @Test
    void shouldStoreErrorMessage() {
        // Given
        TaskRun taskRun = createTaskRun("error-task", "Error Task", TaskRun.TaskStatus.RUNNING);
        entityManager.persist(taskRun);
        entityManager.flush();

        // When
        taskRun.setStatus(TaskRun.TaskStatus.FAILED);
        taskRun.setLastError("Connection timeout after 30s");
        taskRun.setFinishedAt(LocalDateTime.now());
        TaskRun updated = taskRunRepository.save(taskRun);

        // Then
        assertThat(updated.getStatus()).isEqualTo(TaskRun.TaskStatus.FAILED);
        assertThat(updated.getLastError()).isEqualTo("Connection timeout after 30s");
        assertThat(updated.getFinishedAt()).isNotNull();
    }

    private TaskRun createTaskRun(String taskId, String taskName, TaskRun.TaskStatus status) {
        TaskRun taskRun = new TaskRun();
        taskRun.setWorkflowRun(workflowRun);
        taskRun.setTaskId(taskId);
        taskRun.setTaskName(taskName);
        taskRun.setStatus(status);
        taskRun.setMaxRetries(3);
        return taskRun;
    }
}

