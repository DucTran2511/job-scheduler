package com.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO for detailed workflow run information including all tasks
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowRunDetailDTO {
    private String id;
    private String workflowId;
    private String workflowName;
    private String workflowDescription;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationSeconds;
    private Integer totalTasks;
    private Integer completedTasks;
    private Integer failedTasks;
    private Integer runningTasks;
    private Integer pendingTasks;
    private List<TaskRunDTO> tasks;
}

