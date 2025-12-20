package com.api.controller;

import com.api.orchestrator.WorkflowOrchestrator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
@Slf4j
public class WorkflowController {

    private final WorkflowOrchestrator orchestrator;

    @PostMapping(value = "/start", consumes = "text/plain")
    public ResponseEntity<?> startWorkflow(@RequestBody String yamlOrJson) {
        try {
            String runId = orchestrator.startWorkflow(yamlOrJson);
            return ResponseEntity.ok().body(new StartResponse(runId));

        } catch (JsonParseException e){
            log.error("User send bad payload", e);
            return ResponseEntity.badRequest().body(new ErrorResponse("parse_error", e.getMessage()));
        }catch (Exception e) {
            log.error("Failed to start workflow", e);
            return ResponseEntity.badRequest().body(new ErrorResponse("system_error", e.getMessage()));
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

    @PostMapping("/task/callback")
    public ResponseEntity<Void> onTaskCallback(@RequestBody Map<String, Object> body) {
        String workflowRunId = (String) body.get("workflowRunId");
        String taskId = (String) body.get("taskId");
        boolean success = (boolean) body.get("success");
        String lastError = (String) body.get("lastError");

        orchestrator.onTaskCompleted(workflowRunId, taskId, success, lastError);
        return ResponseEntity.ok().build();
    }

    record StartResponse(String workflowRunId) {}
    record ErrorResponse(String code, String message) {}
}
