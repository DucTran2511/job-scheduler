package com.api.repository;

import com.api.entity.WorkflowEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class WorkflowRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Test
    void shouldSaveAndRetrieveWorkflow() {
        // Given
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setName("daily-backup");
        workflow.setDescription("Daily backup workflow");
        workflow.setRawDefinition("workflow:\n  name: daily-backup\n  tasks:\n    - id: backup");
        workflow.setCreatedAt(LocalDateTime.now());

        // When
        WorkflowEntity saved = workflowRepository.save(workflow);

        // Then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getName()).isEqualTo("daily-backup");
        assertThat(saved.getDescription()).isEqualTo("Daily backup workflow");
        assertThat(saved.getRawDefinition()).contains("daily-backup");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void shouldFindWorkflowById() {
        // Given
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setName("test-workflow");
        workflow.setDescription("Test workflow");
        workflow.setRawDefinition("workflow:\n  name: test");
        entityManager.persist(workflow);
        entityManager.flush();

        // When
        Optional<WorkflowEntity> found = workflowRepository.findById(workflow.getId());

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("test-workflow");
    }

    @Test
    void shouldFindAllWorkflows() {
        // Given
        WorkflowEntity workflow1 = createWorkflow("workflow-1", "First workflow");
        WorkflowEntity workflow2 = createWorkflow("workflow-2", "Second workflow");
        WorkflowEntity workflow3 = createWorkflow("workflow-3", "Third workflow");

        entityManager.persist(workflow1);
        entityManager.persist(workflow2);
        entityManager.persist(workflow3);
        entityManager.flush();

        // When
        List<WorkflowEntity> workflows = workflowRepository.findAll();

        // Then
        assertThat(workflows).hasSize(3);
        assertThat(workflows).extracting(WorkflowEntity::getName)
                .containsExactlyInAnyOrder("workflow-1", "workflow-2", "workflow-3");
    }

    @Test
    void shouldUpdateWorkflow() {
        // Given
        WorkflowEntity workflow = createWorkflow("update-workflow", "Original description");
        entityManager.persist(workflow);
        entityManager.flush();

        // When
        workflow.setDescription("Updated description");
        workflow.setRawDefinition("workflow:\n  name: updated");
        WorkflowEntity updated = workflowRepository.save(workflow);

        // Then
        assertThat(updated.getDescription()).isEqualTo("Updated description");
        assertThat(updated.getRawDefinition()).contains("updated");
    }

    @Test
    void shouldDeleteWorkflow() {
        // Given
        WorkflowEntity workflow = createWorkflow("delete-workflow", "To be deleted");
        entityManager.persist(workflow);
        entityManager.flush();
        String workflowId = workflow.getId();

        // When
        workflowRepository.deleteById(workflowId);

        // Then
        Optional<WorkflowEntity> deleted = workflowRepository.findById(workflowId);
        assertThat(deleted).isEmpty();
    }

    @Test
    void shouldEnforceUniqueWorkflowName() {
        // Given
        WorkflowEntity workflow1 = createWorkflow("duplicate-name", "First");
        WorkflowEntity workflow2 = createWorkflow("duplicate-name", "Second");

        entityManager.persist(workflow1);
        entityManager.flush();

        // When & Then
        assertThatThrownBy(() -> {
            entityManager.persist(workflow2);
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldCountWorkflows() {
        // Given
        entityManager.persist(createWorkflow("workflow-1", "First"));
        entityManager.persist(createWorkflow("workflow-2", "Second"));
        entityManager.flush();

        // When
        long count = workflowRepository.count();

        // Then
        assertThat(count).isEqualTo(2);
    }

    @Test
    void shouldCheckIfWorkflowExists() {
        // Given
        WorkflowEntity workflow = createWorkflow("existing-workflow", "Exists");
        entityManager.persist(workflow);
        entityManager.flush();

        // When
        boolean exists = workflowRepository.existsById(workflow.getId());
        boolean notExists = workflowRepository.existsById("non-existent-id");

        // Then
        assertThat(exists).isTrue();
        assertThat(notExists).isFalse();
    }

    private WorkflowEntity createWorkflow(String name, String description) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setName(name);
        workflow.setDescription(description);
        workflow.setRawDefinition("workflow:\n  name: " + name);
        workflow.setCreatedAt(LocalDateTime.now());
        return workflow;
    }
}

