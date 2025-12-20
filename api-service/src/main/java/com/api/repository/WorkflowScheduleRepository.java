package com.api.repository;

import com.api.entity.WorkflowSchedule;
import com.api.entity.WorkflowSchedule.ScheduleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface WorkflowScheduleRepository extends JpaRepository<WorkflowSchedule, String> {

    @Query("SELECT s FROM WorkflowSchedule s WHERE s.status = :status AND s.nextRunAt <= :now")
    List<WorkflowSchedule> findDueSchedules(@Param("status") ScheduleStatus status, @Param("now") LocalDateTime now);

    List<WorkflowSchedule> findByStatus(ScheduleStatus status);
}
