package com.api.orchestrator;

import com.api.entity.TaskRun;
import com.api.repository.TaskRunRepository;
import com.common.service.RedisHeartbeatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;

@Component
@RequiredArgsConstructor
@Slf4j
public class ZombieTaskReaper {

    private final TaskRunRepository taskRunRepository;
    private final RedisHeartbeatService heartbeatService;
    private final WorkflowOrchestrator orchestrator;

    @Value("${job.scheduler.zombie.grace-period-seconds:45}")
    private int gracePeriodSeconds;

    @Scheduled(fixedRateString = "${job.scheduler.zombie.check-rate-ms:30000}")
    public void reapZombieTasks() {
        List<TaskRun> runningTasks = taskRunRepository.findByStatus(TaskRun.TaskStatus.RUNNING);

        for (TaskRun task : runningTasks) {
            if (task.getStartedAt() != null &&
                    task.getStartedAt().isAfter(LocalDateTime.now().minusSeconds(gracePeriodSeconds))) {
                continue;
            }

            boolean isAlive = heartbeatService.isAlive(task.getWorkflowRun().getId(), task.getTaskId());

            if (!isAlive) {
                log.warn("Detected Zombie Task: run={} task={}. Marking as FAILED.",
                        task.getWorkflowRun().getId(), task.getTaskId());

                orchestrator.onTaskCompleted(
                        task.getWorkflowRun().getId(),
                        task.getTaskId(),
                        false,
                        "Worker crashed (Heartbeat timeout)");
            }
        }
    }
}
