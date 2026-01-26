package com.api.entity;

import jakarta.persistence.Id;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "task_runs")
@Getter
@Setter
public class TaskRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_run_id")
    private WorkflowRun workflowRun;

    @Column(nullable = false)
    private String taskId;

    private String taskName;
    private String command;
    private String taskType;
    private Integer timeoutSeconds;

    @Enumerated(EnumType.STRING)
    private TaskStatus status = TaskStatus.PENDING;

    private int retryCount = 0;
    private int maxRetries = 3;
    private String lastError;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    public enum TaskStatus {
        PENDING, RUNNING, SUCCESS, FAILED
    }
}
