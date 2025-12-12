package com.api.service;

import com.api.dto.TaskRunDTO;
import com.api.dto.WorkflowRunDTO;
import com.api.dto.WorkflowRunDetailDTO;
import com.api.entity.TaskRun;
import com.api.entity.WorkflowRun;

import com.api.repository.TaskRunRepository;
import com.api.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowQueryService {

    private final WorkflowRunRepository workflowRunRepository;
    private final TaskRunRepository taskRunRepository;

    @Transactional(readOnly = true)
    public Page<WorkflowRunDTO> listWorkflows(int page, int size, String status) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt"));

        Page<WorkflowRun> workflowRuns;

        if (status != null && !status.isEmpty()) {
            try {
                WorkflowRun.RunStatus runStatus = WorkflowRun.RunStatus.valueOf(status.toUpperCase());
                workflowRuns = workflowRunRepository.findByStatus(runStatus, pageable);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid status filter: {}, returning all workflows", status);
                workflowRuns = workflowRunRepository.findAll(pageable);
            }
        } else {
            workflowRuns = workflowRunRepository.findAll(pageable);
        }

        return workflowRuns.map(WorkflowRunDTO::from);
    }

    @Transactional(readOnly = true)
    public WorkflowRunDetailDTO getWorkflowDetail(String runId) {
        return null;
    }

    @Transactional(readOnly = true)
    public List<TaskRunDTO> getWorkflowTasks(String runId) {
        return null;
    }
}
