package com.api.repository;

import com.api.entity.TaskRun;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TaskRunRepository extends JpaRepository<TaskRun, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TaskRun t WHERE t.workflowRunId = :runId AND t.taskId = :taskId")
    Optional<TaskRun> findByWorkflowRunIdAndTaskIdWithLock(String runId, String taskId);

    TaskRun findByWorkflowRunIdAndTaskId(String workflowRunId, String taskId);

    List<TaskRun> findByWorkflowRunId(String workflowRunId);

    List<TaskRun> findByTaskId(String taskId);

    List<TaskRun> findByStatus(TaskRun.TaskStatus status);

    long countByWorkflowRunIdAndStatusNot(String workflowRunId, TaskRun.TaskStatus status);
}
