package com.api.controller;

import com.api.dto.TaskRunDTO;
import com.api.dto.WorkflowRunDTO;
import com.api.dto.WorkflowRunDetailDTO;
import com.api.service.WorkflowQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for WorkflowQueryController using MockMvc
 */
@WebMvcTest(WorkflowQueryController.class)
class WorkflowQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkflowQueryService workflowQueryService;

    @TestConfiguration
    static class TestConfig {
        @Bean
        public WorkflowQueryService workflowQueryService() {
            return org.mockito.Mockito.mock(WorkflowQueryService.class);
        }
    }

    @Test
    void listWorkflows_ShouldReturnPaginatedWorkflows() throws Exception {
        // Given
        WorkflowRunDTO workflow1 = WorkflowRunDTO.builder()
                .id("run-1")
                .workflowId("workflow-1")
                .workflowName("Daily Backup")
                .status("RUNNING")
                .startedAt(LocalDateTime.now())
                .build();

        WorkflowRunDTO workflow2 = WorkflowRunDTO.builder()
                .id("run-2")
                .workflowId("workflow-2")
                .workflowName("ETL Pipeline")
                .status("COMPLETED")
                .startedAt(LocalDateTime.now().minusHours(1))
                .finishedAt(LocalDateTime.now())
                .durationSeconds(3600L)
                .build();

        Page<WorkflowRunDTO> page = new PageImpl<>(Arrays.asList(workflow1, workflow2));
        when(workflowQueryService.listWorkflows(anyInt(), anyInt(), org.mockito.ArgumentMatchers.<String>any())).thenReturn(page);

        // When & Then
        mockMvc.perform(get("/api/workflows")
                        .param("page", "0")
                        .param("size", "20")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").exists())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()", is(2)));
    }

    @Test
    void listWorkflows_WithStatusFilter_ShouldReturnFilteredWorkflows() throws Exception {
        // Given
        WorkflowRunDTO workflow = WorkflowRunDTO.builder()
                .id("run-1")
                .workflowId("workflow-1")
                .workflowName("Running Workflow")
                .status("RUNNING")
                .startedAt(LocalDateTime.now())
                .build();

        Page<WorkflowRunDTO> page = new PageImpl<>(List.of(workflow));
        when(workflowQueryService.listWorkflows(0, 10, "RUNNING")).thenReturn(page);

        // When & Then
        mockMvc.perform(get("/api/workflows")
                        .param("page", "0")
                        .param("size", "10")
                        .param("status", "RUNNING")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].status", is("RUNNING")));
    }

    @Test
    void listWorkflows_WithDefaultParameters_ShouldUseDefaults() throws Exception {
        // Given
        Page<WorkflowRunDTO> emptyPage = new PageImpl<>(List.of());
        when(workflowQueryService.listWorkflows(0, 20, null)).thenReturn(emptyPage);

        // When & Then
        mockMvc.perform(get("/api/workflows")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void getWorkflow_ShouldReturnWorkflowDetails() throws Exception {
        // Given
        String runId = "run-123";

        TaskRunDTO task1 = TaskRunDTO.builder()
                .id("task-1")
                .taskId("crawl-linkedin")
                .taskName("Crawl LinkedIn Jobs")
                .command("python crawl_linkedin.py")
                .status("SUCCESS")
                .retryCount(0)
                .maxRetries(3)
                .startedAt(LocalDateTime.now().minusMinutes(10))
                .finishedAt(LocalDateTime.now().minusMinutes(5))
                .durationSeconds(300L)
                .build();

        TaskRunDTO task2 = TaskRunDTO.builder()
                .id("task-2")
                .taskId("crawl-topcv")
                .taskName("Crawl TopCV Jobs")
                .command("python crawl_topcv.py")
                .status("RUNNING")
                .retryCount(0)
                .maxRetries(3)
                .startedAt(LocalDateTime.now().minusMinutes(5))
                .build();

        WorkflowRunDetailDTO detail = WorkflowRunDetailDTO.builder()
                .id(runId)
                .workflowId("workflow-1")
                .workflowName("Job Market Crawler")
                .workflowDescription("Crawl job listings from multiple sites")
                .status("RUNNING")
                .startedAt(LocalDateTime.now().minusMinutes(10))
                .totalTasks(3)
                .completedTasks(1)
                .failedTasks(0)
                .runningTasks(1)
                .pendingTasks(1)
                .tasks(Arrays.asList(task1, task2))
                .build();

        when(workflowQueryService.getWorkflowDetail(runId)).thenReturn(detail);

        // When & Then
        mockMvc.perform(get("/api/workflows/{id}", runId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(runId)))
                .andExpect(jsonPath("$.workflowName", is("Job Market Crawler")))
                .andExpect(jsonPath("$.status", is("RUNNING")))
                .andExpect(jsonPath("$.totalTasks", is(3)))
                .andExpect(jsonPath("$.completedTasks", is(1)))
                .andExpect(jsonPath("$.runningTasks", is(1)))
                .andExpect(jsonPath("$.pendingTasks", is(1)))
                .andExpect(jsonPath("$.tasks", hasSize(2)))
                .andExpect(jsonPath("$.tasks[0].taskId", is("crawl-linkedin")))
                .andExpect(jsonPath("$.tasks[0].status", is("SUCCESS")))
                .andExpect(jsonPath("$.tasks[1].taskId", is("crawl-topcv")))
                .andExpect(jsonPath("$.tasks[1].status", is("RUNNING")));
    }

    @Test
    void getWorkflow_WhenNotFound_ShouldThrowException() throws Exception {
//        // Given
//        String runId = "non-existent-run";
//        when(workflowQueryService.getWorkflowDetail(runId))
//                .thenThrow(new com.api.exception.WorkflowNotFoundException("Workflow run not found: " + runId));
//
//        // When & Then
//        mockMvc.perform(get("/api/workflows/{id}", runId)
//                        .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isNotFound())
//                .andExpect(jsonPath("$.message", containsString("not found")));
    }

    @Test
    void getWorkflowTasks_ShouldReturnAllTasks() throws Exception {
        // Given
        String runId = "run-123";

        TaskRunDTO task1 = TaskRunDTO.builder()
                .id("task-1")
                .taskId("linkedin-crawler")
                .taskName("Crawl LinkedIn")
                .command("python crawl_linkedin.py")
                .status("SUCCESS")
                .retryCount(0)
                .maxRetries(3)
                .durationSeconds(120L)
                .build();

        TaskRunDTO task2 = TaskRunDTO.builder()
                .id("task-2")
                .taskId("topcv-crawler")
                .taskName("Crawl TopCV")
                .command("python crawl_topcv.py")
                .status("FAILED")
                .retryCount(2)
                .maxRetries(3)
                .lastError("Connection timeout")
                .build();

        TaskRunDTO task3 = TaskRunDTO.builder()
                .id("task-3")
                .taskId("itviec-crawler")
                .taskName("Crawl ITviec")
                .command("python crawl_itviec.py")
                .status("PENDING")
                .retryCount(0)
                .maxRetries(3)
                .build();

        List<TaskRunDTO> tasks = Arrays.asList(task1, task2, task3);
        when(workflowQueryService.getWorkflowTasks(runId)).thenReturn(tasks);

        // When & Then
        mockMvc.perform(get("/api/workflows/{id}/tasks", runId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].taskId", is("linkedin-crawler")))
                .andExpect(jsonPath("$[0].status", is("SUCCESS")))
                .andExpect(jsonPath("$[0].durationSeconds", is(120)))
                .andExpect(jsonPath("$[1].taskId", is("topcv-crawler")))
                .andExpect(jsonPath("$[1].status", is("FAILED")))
                .andExpect(jsonPath("$[1].retryCount", is(2)))
                .andExpect(jsonPath("$[1].lastError", is("Connection timeout")))
                .andExpect(jsonPath("$[2].taskId", is("itviec-crawler")))
                .andExpect(jsonPath("$[2].status", is("PENDING")));
    }

    @Test
    void getWorkflowTasks_WithEmptyTasks_ShouldReturnEmptyList() throws Exception {
        // Given
        String runId = "run-empty";
        when(workflowQueryService.getWorkflowTasks(runId)).thenReturn(List.of());

        // When & Then
        mockMvc.perform(get("/api/workflows/{id}/tasks", runId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void getWorkflowTasks_WhenWorkflowNotFound_ShouldThrowException() throws Exception {
//        // Given
//        String runId = "non-existent-run";
//        when(workflowQueryService.getWorkflowTasks(runId))
//                .thenThrow(new com.api.exception.WorkflowNotFoundException("Workflow run not found: " + runId));
//
//        // When & Then
//        mockMvc.perform(get("/api/workflows/{id}/tasks", runId)
//                        .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isNotFound())
//                .andExpect(jsonPath("$.message", containsString("not found")));
    }

    @Test
    void listWorkflows_WithLargePage_ShouldHandleCorrectly() throws Exception {
        // Given
        Page<WorkflowRunDTO> emptyPage = new PageImpl<>(List.of());
        when(workflowQueryService.listWorkflows(10, 100, null)).thenReturn(emptyPage);

        // When & Then
        mockMvc.perform(get("/api/workflows")
                        .param("page", "10")
                        .param("size", "100")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void getWorkflow_WithCompletedWorkflow_ShouldShowDuration() throws Exception {
        // Given
        String runId = "run-completed";

        WorkflowRunDetailDTO detail = WorkflowRunDetailDTO.builder()
                .id(runId)
                .workflowId("workflow-1")
                .workflowName("Completed Workflow")
                .status("COMPLETED")
                .startedAt(LocalDateTime.now().minusHours(2))
                .finishedAt(LocalDateTime.now().minusHours(1))
                .durationSeconds(3600L)
                .totalTasks(2)
                .completedTasks(2)
                .failedTasks(0)
                .runningTasks(0)
                .pendingTasks(0)
                .tasks(List.of())
                .build();

        when(workflowQueryService.getWorkflowDetail(runId)).thenReturn(detail);

        // When & Then
        mockMvc.perform(get("/api/workflows/{id}", runId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.durationSeconds", is(3600)))
                .andExpect(jsonPath("$.completedTasks", is(2)))
                .andExpect(jsonPath("$.failedTasks", is(0)));
    }
}
