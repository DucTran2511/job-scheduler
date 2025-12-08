package com.api.repository;

import com.api.entity.WorkflowRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, String> {
    Page<WorkflowRun> findByStatus(WorkflowRun.RunStatus status, Pageable pageable);

    // Count workflows by status (for health checks)
    long countByStatus(WorkflowRun.RunStatus status);
}
