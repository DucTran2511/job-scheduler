package com.api.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "schedule_executions")
@Getter
@Setter
public class ScheduleExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private WorkflowSchedule schedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_run_id")
    private WorkflowRun workflowRun;

    private LocalDateTime scheduledTime;
    private LocalDateTime actualTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TriggerType triggerType = TriggerType.SCHEDULED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionStatus status = ExecutionStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private String triggeredBy;
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum TriggerType {
        SCHEDULED,
        MANUAL,
        CATCH_UP,
        API
    }

    public enum ExecutionStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        SKIPPED
    }
}
