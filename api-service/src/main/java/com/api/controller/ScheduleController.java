package com.api.controller;

import com.api.dto.WorkflowScheduleRequestDTO;
import com.api.dto.WorkflowScheduleResponseDTO;
import com.api.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
@Slf4j
public class ScheduleController {

    private final ScheduleService scheduleService;

    @PostMapping
    public ResponseEntity<WorkflowScheduleResponseDTO> createSchedule(@RequestBody WorkflowScheduleRequestDTO request) {
        log.info("Creating schedule: name={}", request.getName());
        WorkflowScheduleResponseDTO response = scheduleService.createSchedule(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
