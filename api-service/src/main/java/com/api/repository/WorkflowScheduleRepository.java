package com.api.repository;

import com.api.entity.WorkflowSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowScheduleRepository extends JpaRepository<WorkflowSchedule, String> {
}
