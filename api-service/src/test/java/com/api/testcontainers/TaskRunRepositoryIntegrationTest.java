package com.api.testcontainers;

import com.api.entity.TaskRun;
import com.api.entity.WorkflowEntity;
import com.api.entity.WorkflowRun;
import com.api.repository.TaskRunRepository;
import com.api.repository.WorkflowRepository;
import com.api.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainer integration tests for TaskRunRepository.
 * Tests custom queries, task lifecycle, and retry logic against real PostgreSQL.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TaskRunRepositoryIntegrationTest extends BaseTestcontainersTest {

    @Autowired
    private TaskRunRepository taskRunRepository;

    @Autowired
    private WorkflowRunRepository workflowRunRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    private WorkflowRun testWorkflowRun;

    @BeforeEach
    void setUp() {
        taskRunRepository.deleteAll();
        workflowRunRepository.deleteAll();
        workflowRepository.deleteAll();

        // Create test data
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setName("test-workflow");
        workflow.setDescription("Test workflow");
        workflow = workflowRepository.save(workflow);

        testWorkflowRun = new WorkflowRun();
        testWorkflowRun.setWorkflow(workflow);
        testWorkflowRun.setStatus(WorkflowRun.RunStatus.RUNNING);
        testWorkflowRun.setStartedAt(LocalDateTime.now());
        testWorkflowRun = workflowRunRepository.save(testWorkflowRun);
    }

    @Test
    void shouldSaveAndFindTaskRun() {
        // Given
        TaskRun task = createTaskRun("task1", "Crawl LinkedIn", "curl linkedin.com");

        // When
        TaskRun saved = taskRunRepository.save(task);

        // Then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTaskId()).isEqualTo("task1");
        assertThat(saved.getStatus()).isEqualTo(TaskRun.TaskStatus.PENDING);
    }

    @Test
    void shouldFindByWorkflowRunId() {
        // Given
        taskRunRepository.save(createTaskRun("task1", "Task 1", "cmd1"));
        taskRunRepository.save(createTaskRun("task2", "Task 2", "cmd2"));
        taskRunRepository.save(createTaskRun("task3", "Task 3", "cmd3"));

        // When
        List<TaskRun> tasks = taskRunRepository.findByWorkflowRunId(testWorkflowRun.getId());

        // Then
        assertThat(tasks).hasSize(3);
        assertThat(tasks)
                .extracting(TaskRun::getTaskId)
                .containsExactlyInAnyOrder("task1", "task2", "task3");
    }

    @Test
    void shouldFindByWorkflowRunIdAndTaskId() {
        // Given
        taskRunRepository.save(createTaskRun("task1", "Task 1", "cmd1"));
        taskRunRepository.save(createTaskRun("task2", "Task 2", "cmd2"));

        // When
        TaskRun found = taskRunRepository.findByWorkflowRunIdAndTaskId(
                testWorkflowRun.getId(), "task1");

        // Then
        assertThat(found).isNotNull();
        assertThat(found.getTaskId()).isEqualTo("task1");
        assertThat(found.getTaskName()).isEqualTo("Task 1");
    }

    @Test
    void shouldFindByTaskId() {
        // Given - Create multiple runs, some with same taskId
        taskRunRepository.save(createTaskRun("linkedin-crawler", "LinkedIn", "cmd"));
        taskRunRepository.save(createTaskRun("topcv-crawler", "TopCV", "cmd"));
        taskRunRepository.save(createTaskRun("linkedin-crawler", "LinkedIn Retry", "cmd"));

        // When
        List<TaskRun> linkedInTasks = taskRunRepository.findByTaskId("linkedin-crawler");

        // Then
        assertThat(linkedInTasks).hasSize(2);
    }

    @Test
    void shouldFindByStatus() {
        // Given
        TaskRun pending1 = createTaskRun("task1", "Task 1", "cmd1");
        TaskRun pending2 = createTaskRun("task2", "Task 2", "cmd2");
        TaskRun running = createTaskRun("task3", "Task 3", "cmd3");
        running.setStatus(TaskRun.TaskStatus.RUNNING);

        taskRunRepository.saveAll(List.of(pending1, pending2, running));

        // When
        List<TaskRun> pendingTasks = taskRunRepository.findByStatus(TaskRun.TaskStatus.PENDING);
        List<TaskRun> runningTasks = taskRunRepository.findByStatus(TaskRun.TaskStatus.RUNNING);

        // Then
        assertThat(pendingTasks).hasSize(2);
        assertThat(runningTasks).hasSize(1);
    }

    @Test
    void shouldTransitionFromPendingToRunning() {
        // Given
        TaskRun task = createTaskRun("task1", "Test Task", "echo test");
        TaskRun saved = taskRunRepository.save(task);

        // When
        saved.setStatus(TaskRun.TaskStatus.RUNNING);
        saved.setStartedAt(LocalDateTime.now());
        TaskRun updated = taskRunRepository.save(saved);

        // Then
        assertThat(updated.getStatus()).isEqualTo(TaskRun.TaskStatus.RUNNING);
        assertThat(updated.getStartedAt()).isNotNull();
    }

    @Test
    void shouldTransitionToSuccess() {
        // Given
        TaskRun task = createTaskRun("task1", "Test Task", "echo success");
        task.setStatus(TaskRun.TaskStatus.RUNNING);
        task.setStartedAt(LocalDateTime.now());
        TaskRun saved = taskRunRepository.save(task);

        // When
        saved.setStatus(TaskRun.TaskStatus.SUCCESS);
        saved.setFinishedAt(LocalDateTime.now());
        TaskRun updated = taskRunRepository.save(saved);

        // Then
        assertThat(updated.getStatus()).isEqualTo(TaskRun.TaskStatus.SUCCESS);
        assertThat(updated.getFinishedAt()).isNotNull();
    }

    @Test
    void shouldHandleTaskFailureWithError() {
        // Given
        TaskRun task = createTaskRun("task1", "Failing Task", "exit 1");
        task.setStatus(TaskRun.TaskStatus.RUNNING);
        TaskRun saved = taskRunRepository.save(task);

        // When
        saved.setStatus(TaskRun.TaskStatus.FAILED);
        saved.setLastError("Connection timeout");
        saved.setFinishedAt(LocalDateTime.now());
        TaskRun updated = taskRunRepository.save(saved);

        // Then
        assertThat(updated.getStatus()).isEqualTo(TaskRun.TaskStatus.FAILED);
        assertThat(updated.getLastError()).isEqualTo("Connection timeout");
    }

    @Test
    void shouldTrackRetryCount() {
        // Given
        TaskRun task = createTaskRun("task1", "Retry Task", "cmd");
        TaskRun saved = taskRunRepository.save(task);

        // When - Simulate retries
        saved.setRetryCount(1);
        saved = taskRunRepository.save(saved);
        saved.setRetryCount(2);
        saved = taskRunRepository.save(saved);

        // Then
        assertThat(saved.getRetryCount()).isEqualTo(2);
        assertThat(saved.getMaxRetries()).isEqualTo(3); // default
    }

    @Test
    void shouldRespectMaxRetries() {
        // Given
        TaskRun task = createTaskRun("task1", "Task with retries", "cmd");
        task.setMaxRetries(5);

        // When
        TaskRun saved = taskRunRepository.save(task);

        // Then
        assertThat(saved.getMaxRetries()).isEqualTo(5);
    }

    @Test
    void shouldHandleJobMarketCrawlerScenario() {
        // Given - Simulating job market crawler workflow
        TaskRun linkedInTask = createTaskRun("linkedin-crawler", "Crawl LinkedIn Jobs",
                "python crawl_linkedin.py");
        TaskRun topCVTask = createTaskRun("topcv-crawler", "Crawl TopCV Jobs",
                "python crawl_topcv.py");
        TaskRun itViecTask = createTaskRun("itviec-crawler", "Crawl ITviec Jobs",
                "python crawl_itviec.py");

        // When - Save all crawler tasks
        taskRunRepository.saveAll(List.of(linkedInTask, topCVTask, itViecTask));

        // Then - Verify all crawlers are tracked
        List<TaskRun> allTasks = taskRunRepository.findByWorkflowRunId(testWorkflowRun.getId());
        assertThat(allTasks).hasSize(3);
        assertThat(allTasks)
                .extracting(TaskRun::getTaskName)
                .containsExactlyInAnyOrder("Crawl LinkedIn Jobs", "Crawl TopCV Jobs", "Crawl ITviec Jobs");
    }

    @Test
    void shouldSimulateCompleteTaskLifecycle() {
        // Given
        TaskRun task = createTaskRun("full-lifecycle", "Complete Task", "python script.py");

        // Step 1: PENDING
        TaskRun saved = taskRunRepository.save(task);
        assertThat(saved.getStatus()).isEqualTo(TaskRun.TaskStatus.PENDING);

        // Step 2: RUNNING
        saved.setStatus(TaskRun.TaskStatus.RUNNING);
        saved.setStartedAt(LocalDateTime.now());
        saved = taskRunRepository.save(saved);
        assertThat(saved.getStatus()).isEqualTo(TaskRun.TaskStatus.RUNNING);

        // Step 3: SUCCESS
        saved.setStatus(TaskRun.TaskStatus.SUCCESS);
        saved.setFinishedAt(LocalDateTime.now());
        saved = taskRunRepository.save(saved);

        // Then
        assertThat(saved.getStatus()).isEqualTo(TaskRun.TaskStatus.SUCCESS);
        assertThat(saved.getStartedAt()).isNotNull();
        assertThat(saved.getFinishedAt()).isNotNull();
        assertThat(saved.getFinishedAt()).isAfter(saved.getStartedAt());
    }

    @Test
    void shouldHandleMultipleWorkflowRuns() {
        // Given - Create another workflow run
        WorkflowRun anotherRun = new WorkflowRun();
        anotherRun.setWorkflow(testWorkflowRun.getWorkflow());
        anotherRun.setStatus(WorkflowRun.RunStatus.RUNNING);
        anotherRun.setStartedAt(LocalDateTime.now());
        anotherRun = workflowRunRepository.save(anotherRun);

        // When - Create tasks for both runs
        TaskRun task1 = createTaskRun("task1", "Task for run 1", "cmd1");
        taskRunRepository.save(task1);

        TaskRun task2 = new TaskRun();
        task2.setWorkflowRun(anotherRun);
        task2.setTaskId("task1");
        task2.setTaskName("Task for run 2");
        task2.setCommand("cmd1");
        taskRunRepository.save(task2);

        // Then
        List<TaskRun> run1Tasks = taskRunRepository.findByWorkflowRunId(testWorkflowRun.getId());
        List<TaskRun> run2Tasks = taskRunRepository.findByWorkflowRunId(anotherRun.getId());

        assertThat(run1Tasks).hasSize(1);
        assertThat(run2Tasks).hasSize(1);
    }

    // Helper method
    private TaskRun createTaskRun(String taskId, String taskName, String command) {
        TaskRun task = new TaskRun();
        task.setWorkflowRun(testWorkflowRun);
        task.setTaskId(taskId);
        task.setTaskName(taskName);
        task.setCommand(command);
        task.setStatus(TaskRun.TaskStatus.PENDING);
        task.setRetryCount(0);
        task.setMaxRetries(3);
        return task;
    }
}

