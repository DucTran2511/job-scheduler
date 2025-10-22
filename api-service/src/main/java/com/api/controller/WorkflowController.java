package com.api.controller;

import com.api.orchestrator.WorkflowOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
@Slf4j
public class WorkflowController {

    private final WorkflowOrchestrator orchestrator;

    /**
     * POST raw YAML/JSON as text/plain body to start a workflow run.
     * Returns the workflowRunId on success.
     */
    @PostMapping(value = "/start", consumes = "text/plain")
    public ResponseEntity<?> startWorkflow(@RequestBody String yamlOrJson) {
        try {
            String runId = orchestrator.startWorkflow(yamlOrJson);
            return ResponseEntity.ok().body(new StartResponse(runId));
        } catch (Exception e) {
            log.error("Failed to start workflow", e);
            return ResponseEntity.badRequest().body(new ErrorResponse("parse_error", e.getMessage()));
        }
    }

    @PostMapping("/{runId}/tasks/{taskId}/complete")
    public ResponseEntity<?> taskCompleted(
            @PathVariable String runId,
            @PathVariable String taskId,
            @RequestParam(defaultValue = "true") boolean success,
            @RequestBody(required = false) String errorPayload
    ) {
        orchestrator.onTaskCompleted(runId, taskId, success, errorPayload);
        return ResponseEntity.ok().build();
    }


    record StartResponse(String workflowRunId) {}
    record ErrorResponse(String code, String message) {}
}


