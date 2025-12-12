package com.api.repository;

import com.api.entity.WorkflowEntity;
import com.api.entity.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class WorkflowRunRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private WorkflowRunRepository workflowRunRepository;

    private WorkflowEntity workflowEntity;

    @BeforeEach
    void setUp() {
        workflowEntity = new WorkflowEntity();
        workflowEntity.setName("test-workflow");
        workflowEntity.setDescription("Test workflow for unit tests");
        workflowEntity.setRawDefinition("workflow:\n  name: test");
        workflowEntity.setCreatedAt(LocalDateTime.now());
        entityManager.persist(workflowEntity);
        entityManager.flush();
    }

    @Test
    void shouldSaveAndRetrieveWorkflowRun() {
        // Given
        WorkflowRun workflowRun = new WorkflowRun();
        workflowRun.setWorkflow(workflowEntity);
        workflowRun.setStatus(WorkflowRun.RunStatus.RUNNING);
        workflowRun.setStartedAt(LocalDateTime.now());

        // When
        WorkflowRun saved = workflowRunRepository.save(workflowRun);

        // Then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(WorkflowRun.RunStatus.RUNNING);
        assertThat(saved.getStartedAt()).isNotNull();
        assertThat(saved.getFinishedAt()).isNull();
    }

    @Test
    void shouldFindWorkflowRunById() {
        // Given
        WorkflowRun workflowRun = new WorkflowRun();
        workflowRun.setWorkflow(workflowEntity);
        workflowRun.setStatus(WorkflowRun.RunStatus.RUNNING);
        entityManager.persist(workflowRun);
        entityManager.flush();

        // When
        Optional<WorkflowRun> found = workflowRunRepository.findById(workflowRun.getId());

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(workflowRun.getId());
        assertThat(found.get().getStatus()).isEqualTo(WorkflowRun.RunStatus.RUNNING);
    }

    @Test
    void shouldUpdateWorkflowRunStatus() {
        // Given
        WorkflowRun workflowRun = new WorkflowRun();
        workflowRun.setWorkflow(workflowEntity);
        workflowRun.setStatus(WorkflowRun.RunStatus.RUNNING);
        workflowRun.setStartedAt(LocalDateTime.now());
        entityManager.persist(workflowRun);
        entityManager.flush();

        // When
        workflowRun.setStatus(WorkflowRun.RunStatus.COMPLETED);
        workflowRun.setFinishedAt(LocalDateTime.now());
        WorkflowRun updated = workflowRunRepository.save(workflowRun);

        // Then
        assertThat(updated.getStatus()).isEqualTo(WorkflowRun.RunStatus.COMPLETED);
        assertThat(updated.getFinishedAt()).isNotNull();
    }

    @Test
    void shouldMarkWorkflowRunAsFailed() {
        // Given
        WorkflowRun workflowRun = new WorkflowRun();
        workflowRun.setWorkflow(workflowEntity);
        workflowRun.setStatus(WorkflowRun.RunStatus.RUNNING);
        entityManager.persist(workflowRun);
        entityManager.flush();

        // When
        workflowRun.setStatus(WorkflowRun.RunStatus.FAILED);
        workflowRun.setFinishedAt(LocalDateTime.now());
        WorkflowRun updated = workflowRunRepository.save(workflowRun);

        // Then
        assertThat(updated.getStatus()).isEqualTo(WorkflowRun.RunStatus.FAILED);
        assertThat(updated.getFinishedAt()).isNotNull();
    }

    @Test
    void shouldDeleteWorkflowRun() {
        // Given
        WorkflowRun workflowRun = new WorkflowRun();
        workflowRun.setWorkflow(workflowEntity);
        workflowRun.setStatus(WorkflowRun.RunStatus.COMPLETED);
        entityManager.persist(workflowRun);
        entityManager.flush();
        String runId = workflowRun.getId();

        // When
        workflowRunRepository.deleteById(runId);

        // Then
        Optional<WorkflowRun> deleted = workflowRunRepository.findById(runId);
        assertThat(deleted).isEmpty();
    }

    @Test
    void shouldCreateMultipleWorkflowRuns() {
        // Given & When
        WorkflowRun run1 = createWorkflowRun(WorkflowRun.RunStatus.COMPLETED);
        WorkflowRun run2 = createWorkflowRun(WorkflowRun.RunStatus.RUNNING);
        WorkflowRun run3 = createWorkflowRun(WorkflowRun.RunStatus.FAILED);

        entityManager.persist(run1);
        entityManager.persist(run2);
        entityManager.persist(run3);
        entityManager.flush();

        // Then
        assertThat(workflowRunRepository.findAll()).hasSize(3);
    }

    private WorkflowRun createWorkflowRun(WorkflowRun.RunStatus status) {
        WorkflowRun workflowRun = new WorkflowRun();
        workflowRun.setWorkflow(workflowEntity);
        workflowRun.setStatus(status);
        workflowRun.setStartedAt(LocalDateTime.now());
        if (status != WorkflowRun.RunStatus.RUNNING) {
            workflowRun.setFinishedAt(LocalDateTime.now());
        }
        return workflowRun;
    }
}

