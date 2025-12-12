package com.api.controller;

import com.api.orchestrator.WorkflowOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkflowController.class)
class WorkflowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowOrchestrator orchestrator;

    @Test
    void startWorkflow_ShouldReturnWorkflowRunId_WhenSuccessful() throws Exception {
        String yamlContent = "workflow:\n  name: test";
        when(orchestrator.startWorkflow(any())).thenReturn("run-123");

        mockMvc.perform(post("/api/workflows/start")
                        .contentType("text/plain")
                        .content(yamlContent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowRunId").value("run-123"));

        verify(orchestrator).startWorkflow(yamlContent);
    }

    @Test
    void startWorkflow_ShouldReturnBadRequest_WhenParseError() throws Exception {
        when(orchestrator.startWorkflow(any()))
                .thenThrow(new RuntimeException("Invalid YAML"));

        mockMvc.perform(post("/api/workflows/start")
                        .contentType("text/plain")
                        .content("invalid yaml"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("parse_error"))
                .andExpect(jsonPath("$.message").value("Invalid YAML"));
    }

    @Test
    void taskCompleted_ShouldCallOrchestrator_WithSuccess() throws Exception {
        mockMvc.perform(post("/api/workflows/run-123/tasks/task-456/complete")
                        .param("success", "true"))
                .andExpect(status().isOk());

        verify(orchestrator).onTaskCompleted("run-123", "task-456", true, null);
    }

    @Test
    void taskCompleted_ShouldCallOrchestrator_WithFailure() throws Exception {
        mockMvc.perform(post("/api/workflows/run-123/tasks/task-456/complete")
                        .param("success", "false")
                        .content("Connection timeout"))
                .andExpect(status().isOk());

        verify(orchestrator).onTaskCompleted("run-123", "task-456", false, "Connection timeout");
    }

    @Test
    void onTaskCallback_ShouldProcessCallback() throws Exception {
        String requestBody = """
            {
                "workflowRunId": "run-789",
                "taskId": "task-101",
                "success": true,
                "lastError": null
            }
            """;

        mockMvc.perform(post("/api/workflows/task/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk());

        verify(orchestrator).onTaskCompleted("run-789", "task-101", true, null);
    }
}
