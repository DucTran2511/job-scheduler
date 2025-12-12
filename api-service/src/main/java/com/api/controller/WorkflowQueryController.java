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

@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
@Slf4j
public class WorkflowQueryController {

    private final WorkflowQueryService workflowQueryService;

    @GetMapping
    public ResponseEntity<Page<WorkflowRunDTO>> listWorkflows(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {

        log.info("Listing workflows - page: {}, size: {}, status: {}", page, size, status);

        Page<WorkflowRunDTO> workflows = workflowQueryService.listWorkflows(page, size, status);

        return ResponseEntity.ok(workflows);
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkflowRunDetailDTO> getWorkflow(@PathVariable String id) {
        log.info("Getting workflow details for run: {}", id);

        WorkflowRunDetailDTO workflow = workflowQueryService.getWorkflowDetail(id);

        return ResponseEntity.ok(workflow);
    }

    @GetMapping("/{id}/tasks")
    public ResponseEntity<List<TaskRunDTO>> getWorkflowTasks(@PathVariable String id) {
        log.info("Getting tasks for workflow run: {}", id);

        List<TaskRunDTO> tasks = workflowQueryService.getWorkflowTasks(id);

        return ResponseEntity.ok(tasks);
    }
}

