package com.api.repository;

import com.api.entity.WorkflowRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, String> {
}

