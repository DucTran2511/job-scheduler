package com.api.repository;

import com.api.entity.TaskRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskRunRepository extends JpaRepository<TaskRun, String> {
    TaskRun findByWorkflowRunIdAndTaskId(String workflowRunId, String taskId);
    List<TaskRun> findByWorkflowRunId(String workflowRunId);
    List<TaskRun> findByTaskId(String taskId);
    List<TaskRun> findByStatus(TaskRun.TaskStatus status);

    long countByWorkflowRunIdAndStatusNot(String workflowRunId, TaskRun.TaskStatus status);
}
