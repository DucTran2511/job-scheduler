package com.api.service;

import com.api.entity.WorkflowEntity;
import com.api.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowService {

    private final WorkflowRepository workflowRepository;

    /**
     * Create a new workflow definition
     */
    @Transactional
    public WorkflowEntity createWorkflow(String name, String description, String rawDefinition) {
        log.info("Creating workflow: {}", name);

        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setName(name);
        workflow.setDescription(description);
        workflow.setRawDefinition(rawDefinition);
        workflow.setCreatedAt(LocalDateTime.now());

        return workflowRepository.save(workflow);
    }

    /**
     * Get workflow by ID
     */
    public Optional<WorkflowEntity> getWorkflowById(String id) {
        log.debug("Fetching workflow by id: {}", id);
        return workflowRepository.findById(id);
    }

    /**
     * Get all workflows
     */
    public List<WorkflowEntity> getAllWorkflows() {
        log.debug("Fetching all workflows");
        return workflowRepository.findAll();
    }

    /**
     * Update an existing workflow
     */
    @Transactional
    public WorkflowEntity updateWorkflow(String id, String description, String rawDefinition) {
        log.info("Updating workflow: {}", id);

        WorkflowEntity workflow = workflowRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + id));

        if (description != null) {
            workflow.setDescription(description);
        }
        if (rawDefinition != null) {
            workflow.setRawDefinition(rawDefinition);
        }

        return workflowRepository.save(workflow);
    }

    /**
     * Delete a workflow by ID
     */
    @Transactional
    public void deleteWorkflow(String id) {
        log.info("Deleting workflow: {}", id);

        if (!workflowRepository.existsById(id)) {
            throw new IllegalArgumentException("Workflow not found: " + id);
        }

        workflowRepository.deleteById(id);
    }

    /**
     * Check if workflow exists
     */
    public boolean workflowExists(String id) {
        return workflowRepository.existsById(id);
    }

    /**
     * Count total workflows
     */
    public long countWorkflows() {
        return workflowRepository.count();
    }
}
