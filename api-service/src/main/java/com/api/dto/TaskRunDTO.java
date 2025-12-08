package com.api.dto;

import com.api.entity.TaskRun;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for task details within a workflow
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskRunDTO {
    private String id;
    private String taskId;
    private String taskName;
    private String command;
    private String status;
    private Integer retryCount;
    private Integer maxRetries;
    private String lastError;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationSeconds;

    public static TaskRunDTO from(TaskRun taskRun) {
        Long duration = null;
        if (taskRun.getStartedAt() != null && taskRun.getFinishedAt() != null) {
            duration = java.time.Duration.between(
                taskRun.getStartedAt(),
                taskRun.getFinishedAt()
            ).getSeconds();
        }

        return TaskRunDTO.builder()
                .id(taskRun.getId())
                .taskId(taskRun.getTaskId())
                .taskName(taskRun.getTaskName())
                .command(taskRun.getCommand())
                .status(taskRun.getStatus() != null ? taskRun.getStatus().name() : null)
                .retryCount(taskRun.getRetryCount())
                .maxRetries(taskRun.getMaxRetries())
                .lastError(taskRun.getLastError())
                .startedAt(taskRun.getStartedAt())
                .finishedAt(taskRun.getFinishedAt())
                .durationSeconds(duration)
                .build();
    }
}

