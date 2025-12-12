package com.api.dto;

import com.api.entity.WorkflowRun;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowRunDTO {
    private String id;
    private String workflowId;
    private String workflowName;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationSeconds;

    public static WorkflowRunDTO from(WorkflowRun workflowRun) {
        Long duration = null;
        if (workflowRun.getStartedAt() != null && workflowRun.getFinishedAt() != null) {
            duration = java.time.Duration.between(
                workflowRun.getStartedAt(),
                workflowRun.getFinishedAt()
            ).getSeconds();
        }

        return WorkflowRunDTO.builder()
                .id(workflowRun.getId())
                .workflowId(workflowRun.getWorkflow() != null ? workflowRun.getWorkflow().getId() : null)
                .workflowName(workflowRun.getWorkflow() != null ? workflowRun.getWorkflow().getName() : null)
                .status(workflowRun.getStatus() != null ? workflowRun.getStatus().name() : null)
                .startedAt(workflowRun.getStartedAt())
                .finishedAt(workflowRun.getFinishedAt())
                .durationSeconds(duration)
                .build();
    }
}

