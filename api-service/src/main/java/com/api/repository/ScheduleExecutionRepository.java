package com.api.repository;

import com.api.entity.ScheduleExecution;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleExecutionRepository extends JpaRepository<ScheduleExecution, String> {
}
