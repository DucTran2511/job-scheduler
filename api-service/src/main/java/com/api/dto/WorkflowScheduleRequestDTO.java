package com.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowScheduleRequestDTO {
    private String name;
    private String description;
    private String cronExpression;
    private String timezone;
    private String scheduleType;
    private String workflowDefinition;
}
