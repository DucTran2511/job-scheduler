package com.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class WorkflowScheduleResponseDTO {
    private String id;
    private String name;
    private String description;
    private String cronExpression;
    private String timezone;
    private String status;
    private LocalDateTime nextRunAt;
    private LocalDateTime lastRunAt;
    private LocalDateTime createdAt;
}
