package com.api.controller;

import com.api.dto.TaskRunDTO;
import com.api.dto.WorkflowRunDTO;
import com.api.dto.WorkflowRunDetailDTO;
import com.api.service.WorkflowQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for querying workflow runs and their status
 */
@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
@Slf4j
public class WorkflowQueryController {

    private final WorkflowQueryService workflowQueryService;

    /**
     * GET /api/workflows - List all workflows with pagination and optional status filter
     *
     * @param page Page number (default: 0)
     * @param size Page size (default: 20)
     * @param status Optional status filter (RUNNING, COMPLETED, FAILED)
     * @return Paginated list of workflow runs
     *
     * Example: GET /api/workflows?page=0&size=10&status=RUNNING
     */
    @GetMapping
    public ResponseEntity<Page<WorkflowRunDTO>> listWorkflows(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {

        log.info("Listing workflows - page: {}, size: {}, status: {}", page, size, status);

        Page<WorkflowRunDTO> workflows = workflowQueryService.listWorkflows(page, size, status);

        return ResponseEntity.ok(workflows);
    }

    /**
     * GET /api/workflows/{id} - Get detailed workflow status with all tasks
     *
     * @param id Workflow run ID
     * @return Detailed workflow information including task statistics and task list
     *
     * Example: GET /api/workflows/550e8400-e29b-41d4-a716-446655440000
     */
    @GetMapping("/{id}")
    public ResponseEntity<WorkflowRunDetailDTO> getWorkflow(@PathVariable String id) {
        log.info("Getting workflow details for run: {}", id);

        WorkflowRunDetailDTO workflow = workflowQueryService.getWorkflowDetail(id);

        return ResponseEntity.ok(workflow);
    }

    /**
     * GET /api/workflows/{id}/tasks - Get all tasks for a specific workflow run
     *
     * @param id Workflow run ID
     * @return List of all tasks in the workflow run
     *
     * Example: GET /api/workflows/550e8400-e29b-41d4-a716-446655440000/tasks
     */
    @GetMapping("/{id}/tasks")
    public ResponseEntity<List<TaskRunDTO>> getWorkflowTasks(@PathVariable String id) {
        log.info("Getting tasks for workflow run: {}", id);

        List<TaskRunDTO> tasks = workflowQueryService.getWorkflowTasks(id);

        return ResponseEntity.ok(tasks);
    }
}

