package com.api.service;

import com.api.dto.WorkflowScheduleRequestDTO;
import com.api.dto.WorkflowScheduleResponseDTO;
import com.api.entity.WorkflowSchedule;
import com.api.exception.InvalidCronExpressionException;
import com.api.exception.InvalidScheduleRequestException;
import com.api.exception.ScheduleNotFoundException;
import com.api.repository.WorkflowScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduleService {

    private final WorkflowScheduleRepository scheduleRepository;

    @Transactional
    public WorkflowScheduleResponseDTO createSchedule(WorkflowScheduleRequestDTO request) {
        validateRequest(request);

        String cronExpr = convertToSpringCron(request.getCronExpression());
        LocalDateTime nextRun = calculateNextRun(cronExpr, request.getTimezone());

        WorkflowSchedule schedule = new WorkflowSchedule();
        schedule.setName(request.getName());
        schedule.setDescription(request.getDescription());
        schedule.setCronExpression(request.getCronExpression());
        schedule.setTimezone(request.getTimezone() != null ? request.getTimezone() : "UTC");
        schedule.setRawDefinition(request.getWorkflowDefinition());
        schedule.setCatchUp(request.getCatchUp() != null ? request.getCatchUp() : false);
        schedule.setMaxConcurrent(request.getMaxConcurrent() != null ? request.getMaxConcurrent() : 1);
        schedule.setNextRunAt(nextRun);
        schedule.setStatus(WorkflowSchedule.ScheduleStatus.ACTIVE);
        schedule.setScheduleType(WorkflowSchedule.ScheduleType.CRON);

        WorkflowSchedule saved = scheduleRepository.save(schedule);
        log.info("Created schedule: id={}, name={}, nextRunAt={}", saved.getId(), saved.getName(), saved.getNextRunAt());

        return toResponseDTO(saved);
    }

    public List<WorkflowScheduleResponseDTO> listAllSchedules() {
        return scheduleRepository.findAll().stream()
                .map(this::toResponseDTO)
                .collect(Collectors.toList());
    }

    public WorkflowScheduleResponseDTO getScheduleById(String id) {
        WorkflowSchedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new ScheduleNotFoundException("Schedule not found: " + id));
        return toResponseDTO(schedule);
    }

    @Transactional
    public void deleteScheduleById(String id){
        WorkflowSchedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new ScheduleNotFoundException("Schedule not found: " + id));
        scheduleRepository.delete(schedule);
        log.info("Deleted schedule: id={}", id);
    }

    @Transactional
    public WorkflowScheduleResponseDTO pauseSchedule(String id) {
        WorkflowSchedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new ScheduleNotFoundException("Schedule not found: " + id));

        if (schedule.getStatus() == WorkflowSchedule.ScheduleStatus.PAUSED) {
            throw new InvalidScheduleRequestException("Schedule is already paused");
        }

        schedule.setStatus(WorkflowSchedule.ScheduleStatus.PAUSED);
        WorkflowSchedule saved = scheduleRepository.save(schedule);
        log.info("Paused schedule: id={}", id);
        return toResponseDTO(saved);
    }

    @Transactional
    public WorkflowScheduleResponseDTO resumeSchedule(String id) {
        WorkflowSchedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new ScheduleNotFoundException("Schedule not found: " + id));

        if (schedule.getStatus() != WorkflowSchedule.ScheduleStatus.PAUSED) {
            throw new InvalidScheduleRequestException("Schedule is not paused");
        }

        String cronExpr = convertToSpringCron(schedule.getCronExpression());
        LocalDateTime nextRun = calculateNextRun(cronExpr, schedule.getTimezone());

        schedule.setStatus(WorkflowSchedule.ScheduleStatus.ACTIVE);
        schedule.setNextRunAt(nextRun);
        WorkflowSchedule saved = scheduleRepository.save(schedule);
        log.info("Resumed schedule: id={}, nextRunAt={}", id, nextRun);
        return toResponseDTO(saved);
    }

    private void validateRequest(WorkflowScheduleRequestDTO request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new InvalidScheduleRequestException("Schedule name is required");
        }
        if (request.getCronExpression() == null || request.getCronExpression().isBlank()) {
            throw new InvalidScheduleRequestException("Cron expression is required");
        }
        if (request.getWorkflowDefinition() == null || request.getWorkflowDefinition().isBlank()) {
            throw new InvalidScheduleRequestException("Workflow definition is required");
        }

        String springCron = convertToSpringCron(request.getCronExpression());
        if (!CronExpression.isValidExpression(springCron)) {
            throw new InvalidCronExpressionException("Invalid cron expression: " + request.getCronExpression());
        }

        if (request.getTimezone() != null) {
            try {
                ZoneId.of(request.getTimezone());
            } catch (Exception e) {
                throw new InvalidScheduleRequestException("Invalid timezone: " + request.getTimezone());
            }
        }
    }

    private String convertToSpringCron(String unixCron) {
        String[] parts = unixCron.trim().split("\\s+");
        if (parts.length == 5) {
            return "0 " + unixCron;
        }
        return unixCron;
    }

    private LocalDateTime calculateNextRun(String cronExpression, String timezone) {
        try {
            CronExpression cron = CronExpression.parse(cronExpression);
            ZoneId zone = timezone != null ? ZoneId.of(timezone) : ZoneId.of("UTC");
            LocalDateTime now = LocalDateTime.now(zone);
            return cron.next(now);
        } catch (Exception e) {
            throw new InvalidCronExpressionException("Failed to calculate next run: " + e.getMessage());
        }
    }

    private WorkflowScheduleResponseDTO toResponseDTO(WorkflowSchedule schedule) {
        return WorkflowScheduleResponseDTO.builder()
                .id(schedule.getId())
                .name(schedule.getName())
                .description(schedule.getDescription())
                .cronExpression(schedule.getCronExpression())
                .timezone(schedule.getTimezone())
                .status(schedule.getStatus().name())
                .nextRunAt(schedule.getNextRunAt())
                .lastRunAt(schedule.getLastRunAt())
                .createdAt(schedule.getCreatedAt())
                .build();
    }
}