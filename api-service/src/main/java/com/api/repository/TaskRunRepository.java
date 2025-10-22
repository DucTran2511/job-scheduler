package com.api.repository;

import com.api.entity.TaskRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRunRepository extends JpaRepository<TaskRun, String> {
    List<TaskRun> findByWorkflowRunId(String workflowRunId);
    List<TaskRun> findByWorkflowRunIdAndTaskIdIn(String workflowRunId, List<String> taskIds);
    TaskRun findByWorkflowRunIdAndTaskId(String workflowRunId, String taskId);
}
