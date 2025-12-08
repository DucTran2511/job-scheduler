package com.api.testcontainers;

import com.api.entity.WorkflowEntity;
import com.api.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainer integration tests for WorkflowRepository.
 * Tests against REAL PostgreSQL 16 container.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WorkflowRepositoryIntegrationTest extends BaseTestcontainersTest {

    @Autowired
    private WorkflowRepository workflowRepository;

    @BeforeEach
    void setUp() {
        workflowRepository.deleteAll();
    }

    @Test
    void shouldSaveAndFindWorkflow() {
        // Given
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setName("daily-backup");
        workflow.setDescription("Daily database backup");
        workflow.setRawDefinition("workflow: backup");

        // When
        WorkflowEntity saved = workflowRepository.save(workflow);

        // Then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getName()).isEqualTo("daily-backup");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void shouldFindWorkflowById() {
        // Given
        WorkflowEntity workflow = createWorkflow("test-workflow", "Test description");
        WorkflowEntity saved = workflowRepository.save(workflow);

        // When
        Optional<WorkflowEntity> found = workflowRepository.findById(saved.getId());

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("test-workflow");
        assertThat(found.get().getDescription()).isEqualTo("Test description");
    }

    @Test
    void shouldReturnEmptyWhenWorkflowNotFound() {
        // When
        Optional<WorkflowEntity> found = workflowRepository.findById("non-existent-id");

        // Then
        assertThat(found).isEmpty();
    }

    @Test
    void shouldFindAllWorkflows() {
        // Given
        workflowRepository.save(createWorkflow("workflow1", "Description 1"));
        workflowRepository.save(createWorkflow("workflow2", "Description 2"));
        workflowRepository.save(createWorkflow("workflow3", "Description 3"));

        // When
        List<WorkflowEntity> workflows = workflowRepository.findAll();

        // Then
        assertThat(workflows).hasSize(3);
        assertThat(workflows)
                .extracting(WorkflowEntity::getName)
                .containsExactlyInAnyOrder("workflow1", "workflow2", "workflow3");
    }

    @Test
    void shouldUpdateWorkflow() {
        // Given
        WorkflowEntity workflow = createWorkflow("original-name", "Original description");
        WorkflowEntity saved = workflowRepository.save(workflow);

        // When
        saved.setName("updated-name");
        saved.setDescription("Updated description");
        WorkflowEntity updated = workflowRepository.save(saved);

        // Then
        assertThat(updated.getId()).isEqualTo(saved.getId());
        assertThat(updated.getName()).isEqualTo("updated-name");
        assertThat(updated.getDescription()).isEqualTo("Updated description");
    }

    @Test
    void shouldDeleteWorkflow() {
        // Given
        WorkflowEntity workflow = createWorkflow("to-delete", "Will be deleted");
        WorkflowEntity saved = workflowRepository.save(workflow);

        // When
        workflowRepository.deleteById(saved.getId());

        // Then
        Optional<WorkflowEntity> found = workflowRepository.findById(saved.getId());
        assertThat(found).isEmpty();
    }

    @Test
    void shouldCountWorkflows() {
        // Given
        workflowRepository.save(createWorkflow("workflow1", "Desc 1"));
        workflowRepository.save(createWorkflow("workflow2", "Desc 2"));

        // When
        long count = workflowRepository.count();

        // Then
        assertThat(count).isEqualTo(2);
    }

    @Test
    void shouldCheckIfWorkflowExists() {
        // Given
        WorkflowEntity workflow = createWorkflow("existing-workflow", "Exists");
        WorkflowEntity saved = workflowRepository.save(workflow);

        // When
        boolean exists = workflowRepository.existsById(saved.getId());
        boolean notExists = workflowRepository.existsById("non-existent-id");

        // Then
        assertThat(exists).isTrue();
        assertThat(notExists).isFalse();
    }

    @Test
    void shouldHandleUniqueConstraintOnName() {
        // Given
        WorkflowEntity workflow1 = createWorkflow("unique-name", "First");
        workflowRepository.save(workflow1);

        // When/Then - saving another workflow with same name should fail
        WorkflowEntity workflow2 = createWorkflow("unique-name", "Second");

        // The database will throw exception due to unique constraint
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> {
                    workflowRepository.save(workflow2);
                    workflowRepository.flush();
                }
        );
    }

    @Test
    void shouldSaveWorkflowWithRawDefinition() {
        // Given
        String yamlDefinition = """
                name: test-workflow
                tasks:
                  - id: task1
                    name: Test Task
                    command: echo test
                """;

        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setName("yaml-workflow");
        workflow.setRawDefinition(yamlDefinition);

        // When
        WorkflowEntity saved = workflowRepository.save(workflow);

        // Then
        assertThat(saved.getRawDefinition()).isEqualTo(yamlDefinition);
    }

    @Test
    void shouldSaveMultipleWorkflowsInBatch() {
        // Given
        List<WorkflowEntity> workflows = List.of(
                createWorkflow("batch1", "Batch 1"),
                createWorkflow("batch2", "Batch 2"),
                createWorkflow("batch3", "Batch 3")
        );

        // When
        List<WorkflowEntity> saved = workflowRepository.saveAll(workflows);

        // Then
        assertThat(saved).hasSize(3);
        assertThat(workflowRepository.count()).isEqualTo(3);
    }

    // Helper method
    private WorkflowEntity createWorkflow(String name, String description) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setName(name);
        workflow.setDescription(description);
        workflow.setRawDefinition("workflow: " + name);
        return workflow;
    }
}

