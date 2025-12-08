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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for WorkflowQueryController with real PostgreSQL database
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowQueryControllerIntegrationTest extends BaseTestcontainersTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowRunRepository workflowRunRepository;

    @Autowired
    private TaskRunRepository taskRunRepository;

    @BeforeEach
    void setUp() {
        taskRunRepository.deleteAll();
        workflowRunRepository.deleteAll();
        workflowRepository.deleteAll();
    }

    @Test
    void listWorkflows_ShouldReturnAllWorkflowRuns() throws Exception {
        // Given - Create test data
        WorkflowEntity workflow1 = createWorkflow("Job Market Crawler", "Crawl job listings");
        WorkflowEntity workflow2 = createWorkflow("Daily ETL", "Daily data processing");

        WorkflowRun run1 = createWorkflowRun(workflow1, WorkflowRun.RunStatus.RUNNING);
        WorkflowRun run2 = createWorkflowRun(workflow2, WorkflowRun.RunStatus.COMPLETED);
        run2.setFinishedAt(LocalDateTime.now());

        // When & Then
        mockMvc.perform(get("/api/workflows")
                        .param("page", "0")
                        .param("size", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[*].workflowName", containsInAnyOrder("Job Market Crawler", "Daily ETL")))
                .andExpect(jsonPath("$.content[*].status", containsInAnyOrder("RUNNING", "COMPLETED")))
                .andExpect(jsonPath("$.totalElements", is(2)));
    }

    @Test
    void listWorkflows_WithStatusFilter_ShouldReturnFilteredResults() throws Exception {
        // Given
        WorkflowEntity workflow = createWorkflow("Test Workflow", "Test");
        createWorkflowRun(workflow, WorkflowRun.RunStatus.RUNNING);
        createWorkflowRun(workflow, WorkflowRun.RunStatus.COMPLETED);
        createWorkflowRun(workflow, WorkflowRun.RunStatus.FAILED);

        // When & Then - Filter by RUNNING
        mockMvc.perform(get("/api/workflows")
                        .param("status", "RUNNING")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].status", is("RUNNING")));

        // When & Then - Filter by COMPLETED
        mockMvc.perform(get("/api/workflows")
                        .param("status", "COMPLETED")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].status", is("COMPLETED")));
    }

    @Test
    void getWorkflow_ShouldReturnDetailedInformation() throws Exception {
        // Given - Create workflow with tasks
        WorkflowEntity workflow = createWorkflow("Job Crawler", "Crawl LinkedIn, TopCV, ITviec");
        WorkflowRun run = createWorkflowRun(workflow, WorkflowRun.RunStatus.RUNNING);

        // Create tasks
        TaskRun task1 = createTaskRun(run, "linkedin-crawler", "Crawl LinkedIn",
                TaskRun.TaskStatus.SUCCESS);
        task1.setFinishedAt(LocalDateTime.now());
        taskRunRepository.save(task1);

        TaskRun task2 = createTaskRun(run, "topcv-crawler", "Crawl TopCV",
                TaskRun.TaskStatus.RUNNING);
        taskRunRepository.save(task2);

        TaskRun task3 = createTaskRun(run, "itviec-crawler", "Crawl ITviec",
                TaskRun.TaskStatus.PENDING);
        taskRunRepository.save(task3);

        // When & Then
        mockMvc.perform(get("/api/workflows/{id}", run.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(run.getId())))
                .andExpect(jsonPath("$.workflowName", is("Job Crawler")))
                .andExpect(jsonPath("$.workflowDescription", is("Crawl LinkedIn, TopCV, ITviec")))
                .andExpect(jsonPath("$.status", is("RUNNING")))
                .andExpect(jsonPath("$.totalTasks", is(3)))
                .andExpect(jsonPath("$.completedTasks", is(1)))
                .andExpect(jsonPath("$.runningTasks", is(1)))
                .andExpect(jsonPath("$.pendingTasks", is(1)))
                .andExpect(jsonPath("$.failedTasks", is(0)))
                .andExpect(jsonPath("$.tasks", hasSize(3)))
                .andExpect(jsonPath("$.tasks[*].taskId",
                        containsInAnyOrder("linkedin-crawler", "topcv-crawler", "itviec-crawler")));
    }

    @Test
    void getWorkflow_WithNonExistentId_ShouldReturnError() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/workflows/{id}", "non-existent-id")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is5xxServerError());
    }

    @Test
    void getWorkflowTasks_ShouldReturnAllTasksForWorkflow() throws Exception {
        // Given
        WorkflowEntity workflow = createWorkflow("Multi-Site Crawler", "Crawl multiple job sites");
        WorkflowRun run = createWorkflowRun(workflow, WorkflowRun.RunStatus.RUNNING);

        TaskRun task1 = createTaskRun(run, "linkedin-crawler", "Crawl LinkedIn Jobs",
                TaskRun.TaskStatus.SUCCESS);
        task1.setCommand("python crawl_linkedin.py");
        task1.setStartedAt(LocalDateTime.now().minusMinutes(10));
        task1.setFinishedAt(LocalDateTime.now().minusMinutes(5));
        taskRunRepository.save(task1);

        TaskRun task2 = createTaskRun(run, "topcv-crawler", "Crawl TopCV Jobs",
                TaskRun.TaskStatus.FAILED);
        task2.setCommand("python crawl_topcv.py");
        task2.setRetryCount(2);
        task2.setLastError("Connection timeout");
        taskRunRepository.save(task2);

        // When & Then
        mockMvc.perform(get("/api/workflows/{id}/tasks", run.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].taskId", is("linkedin-crawler")))
                .andExpect(jsonPath("$[0].status", is("SUCCESS")))
                .andExpect(jsonPath("$[0].command", is("python crawl_linkedin.py")))
                .andExpect(jsonPath("$[1].taskId", is("topcv-crawler")))
                .andExpect(jsonPath("$[1].status", is("FAILED")))
                .andExpect(jsonPath("$[1].retryCount", is(2)))
                .andExpect(jsonPath("$[1].lastError", is("Connection timeout")));
    }

    @Test
    void getWorkflowTasks_WithNoTasks_ShouldReturnEmptyList() throws Exception {
        // Given
        WorkflowEntity workflow = createWorkflow("Empty Workflow", "No tasks");
        WorkflowRun run = createWorkflowRun(workflow, WorkflowRun.RunStatus.RUNNING);

        // When & Then
        mockMvc.perform(get("/api/workflows/{id}/tasks", run.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void listWorkflows_WithPagination_ShouldReturnCorrectPage() throws Exception {
        // Given - Create 25 workflow runs
        WorkflowEntity workflow = createWorkflow("Batch Workflow", "Batch processing");
        for (int i = 0; i < 25; i++) {
            createWorkflowRun(workflow, WorkflowRun.RunStatus.COMPLETED);
        }

        // When & Then - First page
        mockMvc.perform(get("/api/workflows")
                        .param("page", "0")
                        .param("size", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(10)))
                .andExpect(jsonPath("$.totalElements", is(25)))
                .andExpect(jsonPath("$.totalPages", is(3)))
                .andExpect(jsonPath("$.number", is(0)));

        // When & Then - Second page
        mockMvc.perform(get("/api/workflows")
                        .param("page", "1")
                        .param("size", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(10)))
                .andExpect(jsonPath("$.number", is(1)));

        // When & Then - Last page
        mockMvc.perform(get("/api/workflows")
                        .param("page", "2")
                        .param("size", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(5)))
                .andExpect(jsonPath("$.number", is(2)));
    }

    @Test
    void getWorkflow_WithCompletedWorkflow_ShouldCalculateDuration() throws Exception {
        // Given
        WorkflowEntity workflow = createWorkflow("Quick Workflow", "Fast execution");
        WorkflowRun run = createWorkflowRun(workflow, WorkflowRun.RunStatus.COMPLETED);
        run.setStartedAt(LocalDateTime.now().minusMinutes(30));
        run.setFinishedAt(LocalDateTime.now());
        workflowRunRepository.save(run);

        // When & Then
        mockMvc.perform(get("/api/workflows/{id}", run.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.durationSeconds").isNumber())
                .andExpect(jsonPath("$.durationSeconds", greaterThan(1700))) // ~30 minutes
                .andExpect(jsonPath("$.finishedAt").exists());
    }

    @Test
    void getWorkflowTasks_ShouldShowTaskDurations() throws Exception {
        // Given
        WorkflowEntity workflow = createWorkflow("Timed Workflow", "Track task durations");
        WorkflowRun run = createWorkflowRun(workflow, WorkflowRun.RunStatus.RUNNING);

        TaskRun task = createTaskRun(run, "timed-task", "Task with duration",
                TaskRun.TaskStatus.SUCCESS);
        task.setStartedAt(LocalDateTime.now().minusMinutes(5));
        task.setFinishedAt(LocalDateTime.now());
        taskRunRepository.save(task);

        // When & Then
        mockMvc.perform(get("/api/workflows/{id}/tasks", run.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].durationSeconds").isNumber())
                .andExpect(jsonPath("$[0].durationSeconds", greaterThan(250))); // ~5 minutes
    }

    @Test
    void completeJobCrawlerWorkflow_ShouldShowAllStatistics() throws Exception {
        // Given - Simulate complete job market crawler workflow
        WorkflowEntity workflow = createWorkflow("Job Market Crawler",
                "Crawl LinkedIn, TopCV, ITviec, pass to AI model");
        WorkflowRun run = createWorkflowRun(workflow, WorkflowRun.RunStatus.RUNNING);

        // LinkedIn - Success
        TaskRun linkedin = createTaskRun(run, "linkedin-crawler", "Crawl LinkedIn",
                TaskRun.TaskStatus.SUCCESS);
        linkedin.setCommand("python crawl_linkedin.py");
        linkedin.setStartedAt(LocalDateTime.now().minusMinutes(20));
        linkedin.setFinishedAt(LocalDateTime.now().minusMinutes(15));
        taskRunRepository.save(linkedin);

        // TopCV - Success
        TaskRun topcv = createTaskRun(run, "topcv-crawler", "Crawl TopCV",
                TaskRun.TaskStatus.SUCCESS);
        topcv.setCommand("python crawl_topcv.py");
        topcv.setStartedAt(LocalDateTime.now().minusMinutes(15));
        topcv.setFinishedAt(LocalDateTime.now().minusMinutes(10));
        taskRunRepository.save(topcv);

        // ITviec - Failed with retry
        TaskRun itviec = createTaskRun(run, "itviec-crawler", "Crawl ITviec",
                TaskRun.TaskStatus.FAILED);
        itviec.setCommand("python crawl_itviec.py");
        itviec.setRetryCount(3);
        itviec.setMaxRetries(3);
        itviec.setLastError("Max retries exceeded");
        taskRunRepository.save(itviec);

        // AI Processing - Running
        TaskRun aiTask = createTaskRun(run, "ai-extraction", "AI Model Extraction",
                TaskRun.TaskStatus.RUNNING);
        aiTask.setCommand("python ai_extract.py");
        aiTask.setStartedAt(LocalDateTime.now().minusMinutes(5));
        taskRunRepository.save(aiTask);

        // When & Then
        mockMvc.perform(get("/api/workflows/{id}", run.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowName", is("Job Market Crawler")))
                .andExpect(jsonPath("$.totalTasks", is(4)))
                .andExpect(jsonPath("$.completedTasks", is(2)))
                .andExpect(jsonPath("$.failedTasks", is(1)))
                .andExpect(jsonPath("$.runningTasks", is(1)))
                .andExpect(jsonPath("$.pendingTasks", is(0)))
                .andExpect(jsonPath("$.tasks[*].taskId",
                        containsInAnyOrder("linkedin-crawler", "topcv-crawler",
                                "itviec-crawler", "ai-extraction")));
    }

    // Helper methods
    private WorkflowEntity createWorkflow(String name, String description) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setName(name);
        workflow.setDescription(description);
        workflow.setRawDefinition("workflow: " + name);
        return workflowRepository.save(workflow);
    }

    private WorkflowRun createWorkflowRun(WorkflowEntity workflow, WorkflowRun.RunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setWorkflow(workflow);
        run.setStatus(status);
        run.setStartedAt(LocalDateTime.now());
        return workflowRunRepository.save(run);
    }

    private TaskRun createTaskRun(WorkflowRun run, String taskId, String taskName,
                                   TaskRun.TaskStatus status) {
        TaskRun task = new TaskRun();
        task.setWorkflowRun(run);
        task.setTaskId(taskId);
        task.setTaskName(taskName);
        task.setStatus(status);
        task.setRetryCount(0);
        task.setMaxRetries(3);
        return task;
    }
}

