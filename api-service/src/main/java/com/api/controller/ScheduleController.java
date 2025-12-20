package com.api.controller;

import com.api.dto.WorkflowScheduleRequestDTO;
import com.api.dto.WorkflowScheduleResponseDTO;
import com.api.scheduler.WorkflowScheduler;
import com.api.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.util.List;
import java.util.Map;



@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
@Slf4j
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final WorkflowScheduler workflowScheduler;

    @PostMapping
    public ResponseEntity<WorkflowScheduleResponseDTO> createSchedule(@RequestBody WorkflowScheduleRequestDTO request) {
        log.info("Creating schedule: name={}", request.getName());
        WorkflowScheduleResponseDTO response = scheduleService.createSchedule(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<WorkflowScheduleResponseDTO>>getAllSchedules(){
        log.info("Getting all schedules");
        List<WorkflowScheduleResponseDTO> schedules = scheduleService.listAllSchedules();
        return ResponseEntity.ok(schedules);
    }

    @GetMapping("/{id")
    public ResponseEntity<WorkflowScheduleResponseDTO> getScheduleById(@PathVariable String id){
        log.info("Getting schedule by id: id={}", id);
        WorkflowScheduleResponseDTO response = scheduleService.getScheduleById(id);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id")
    public ResponseEntity<WorkflowScheduleResponseDTO> deleteScheduleById(@PathVariable String id){
        log.info("Deleting schedule by id: id={}", id);
        scheduleService.deleteScheduleById(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/trigger")
    public ResponseEntity<Map<String, String>> triggerManually(@PathVariable String id) {
        log.info("Manual trigger for schedule: id={}", id);
        String workflowRunId = workflowScheduler.triggerManually(id);
        return ResponseEntity.ok(Map.of(
            "message", "Schedule triggered successfully",
            "workflowRunId", workflowRunId
        ));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<WorkflowScheduleResponseDTO> pauseSchedule(@PathVariable String id) {
        log.info("Pausing schedule: id={}", id);
        WorkflowScheduleResponseDTO response = scheduleService.pauseSchedule(id);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<WorkflowScheduleResponseDTO> resumeSchedule(@PathVariable String id) {
        log.info("Resuming schedule: id={}", id);
        WorkflowScheduleResponseDTO response = scheduleService.resumeSchedule(id);
        return ResponseEntity.ok(response);
    }
}
