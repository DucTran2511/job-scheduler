package com.api.scheduler;

import com.api.entity.ScheduleExecution;
import com.api.entity.WorkflowRun;
import com.api.entity.WorkflowSchedule;
import com.api.entity.WorkflowSchedule.ScheduleStatus;
import com.api.lock.DistributedLock;
import com.api.orchestrator.WorkflowOrchestrator;
import com.api.repository.ScheduleExecutionRepository;
import com.api.repository.WorkflowRunRepository;
import com.api.repository.WorkflowScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowScheduler {

    @Value("${scheduler.lock.timeout-seconds:300}")
    private int lockTimeoutSeconds;

    private final WorkflowScheduleRepository scheduleRepository;
    private final ScheduleExecutionRepository executionRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowOrchestrator orchestrator;
    private final DistributedLock distributedLock;

    @Scheduled(fixedRateString = "${scheduler.poll.interval-ms:60000}")
    public void checkAndTriggerDueSchedules() {
        LocalDateTime now = LocalDateTime.now();
        List<WorkflowSchedule> dueSchedules = scheduleRepository.findDueSchedules(ScheduleStatus.ACTIVE, now);

        if (dueSchedules.isEmpty()) {
            log.debug("No due schedules found at {}", now);
            return;
        }

        log.info("Found {} due schedule(s) to process", dueSchedules.size());

        for (WorkflowSchedule schedule : dueSchedules) {
            processScheduleWithLock(schedule);
        }
    }

    private void processScheduleWithLock(WorkflowSchedule schedule) {
        String lockKey = "schedule:" + schedule.getId();

        if (!distributedLock.tryLock(lockKey, Duration.ofSeconds(lockTimeoutSeconds))) {
            log.debug("Schedule {} is being processed by another instance, skipping", schedule.getId());
            return;
        }

        try {
            WorkflowSchedule freshSchedule = scheduleRepository.findById(schedule.getId()).orElse(null);
            if (freshSchedule == null) {
                log.warn("Schedule {} not found, may have been deleted", schedule.getId());
                return;
            }

            if (freshSchedule.getStatus() != ScheduleStatus.ACTIVE) {
                log.debug("Schedule {} is no longer active, skipping", schedule.getId());
                return;
            }

            if (freshSchedule.getNextRunAt() == null || freshSchedule.getNextRunAt().isAfter(LocalDateTime.now())) {
                log.debug("Schedule {} next run time has changed, skipping", schedule.getId());
                return;
            }

            log.info("Schedule {} locked for processing", schedule.getId());
            triggerSchedule(freshSchedule);

        } catch (Exception e) {
            log.error("Error processing schedule {}: {}", schedule.getId(), e.getMessage(), e);
        } finally {
            distributedLock.unlock(lockKey);
            log.debug("Released lock for schedule {}", schedule.getId());
        }
    }

    @Transactional
    public void triggerSchedule(WorkflowSchedule schedule) {
        log.info("Triggering schedule: id={}, name={}", schedule.getId(), schedule.getName());

        ScheduleExecution execution = new ScheduleExecution();
        execution.setSchedule(schedule);
        execution.setScheduledTime(schedule.getNextRunAt());
        execution.setActualTime(LocalDateTime.now());
        execution.setTriggerType(ScheduleExecution.TriggerType.SCHEDULED);
        execution.setStatus(ScheduleExecution.ExecutionStatus.RUNNING);

        try {
            String workflowRunId = orchestrator.startWorkflow(schedule.getRawDefinition());

            WorkflowRun workflowRun = workflowRunRepository.findById(workflowRunId).orElse(null);
            execution.setWorkflowRun(workflowRun);
            execution.setStatus(ScheduleExecution.ExecutionStatus.COMPLETED);
            log.info("Schedule {} triggered successfully. WorkflowRunId={}", schedule.getId(), workflowRunId);

        } catch (Exception e) {
            log.error("Failed to trigger schedule {}: {}", schedule.getId(), e.getMessage(), e);
            execution.setStatus(ScheduleExecution.ExecutionStatus.FAILED);
            execution.setErrorMessage(e.getMessage());
        }

        executionRepository.save(execution);
        updateScheduleAfterExecution(schedule);
    }

    @Transactional
    public String triggerManually(String scheduleId) {
        WorkflowSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new RuntimeException("Schedule not found: " + scheduleId));

        log.info("Manual trigger for schedule: id={}, name={}", schedule.getId(), schedule.getName());

        ScheduleExecution execution = new ScheduleExecution();
        execution.setSchedule(schedule);
        execution.setScheduledTime(LocalDateTime.now());
        execution.setActualTime(LocalDateTime.now());
        execution.setTriggerType(ScheduleExecution.TriggerType.MANUAL);
        execution.setStatus(ScheduleExecution.ExecutionStatus.RUNNING);

        try {
            String workflowRunId = orchestrator.startWorkflow(schedule.getRawDefinition());

            WorkflowRun workflowRun = workflowRunRepository.findById(workflowRunId).orElse(null);
            execution.setWorkflowRun(workflowRun);
            execution.setStatus(ScheduleExecution.ExecutionStatus.COMPLETED);
            executionRepository.save(execution);

            log.info("Manual trigger successful. WorkflowRunId={}", workflowRunId);
            return workflowRunId;

        } catch (Exception e) {
            log.error("Manual trigger failed for schedule {}: {}", scheduleId, e.getMessage(), e);
            execution.setStatus(ScheduleExecution.ExecutionStatus.FAILED);
            execution.setErrorMessage(e.getMessage());
            executionRepository.save(execution);
            throw new RuntimeException("Failed to trigger workflow: " + e.getMessage(), e);
        }
    }

    private void updateScheduleAfterExecution(WorkflowSchedule schedule) {
        schedule.setLastRunAt(LocalDateTime.now());

        if (schedule.getScheduleType() == WorkflowSchedule.ScheduleType.ONE_TIME) {
            schedule.setStatus(ScheduleStatus.COMPLETED);
            schedule.setNextRunAt(null);
        } else {
            LocalDateTime nextRun = calculateNextRun(schedule);
            schedule.setNextRunAt(nextRun);
        }

        scheduleRepository.save(schedule);
        log.info("Updated schedule {}: nextRunAt={}", schedule.getId(), schedule.getNextRunAt());
    }

    private LocalDateTime calculateNextRun(WorkflowSchedule schedule) {
        try {
            String cronExpr = convertToSpringCron(schedule.getCronExpression());
            CronExpression cron = CronExpression.parse(cronExpr);
            ZoneId zone = ZoneId.of(schedule.getTimezone());
            LocalDateTime now = LocalDateTime.now(zone);
            return cron.next(now);
        } catch (Exception e) {
            log.error("Failed to calculate next run for schedule {}: {}", schedule.getId(), e.getMessage());
            return null;
        }
    }

    private String convertToSpringCron(String unixCron) {
        String[] parts = unixCron.trim().split("\\s+");
        if (parts.length == 5) {
            return "0 " + unixCron;
        }
        return unixCron;
    }
}