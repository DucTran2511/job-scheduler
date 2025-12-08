package com.api.testcontainers;

import com.api.entity.WorkflowEntity;
import com.api.entity.WorkflowRun;
import com.api.repository.WorkflowRepository;
import com.api.repository.WorkflowRunRepository;
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
 * Testcontainer integration tests for WorkflowRunRepository.
 * Tests workflow run lifecycle and status transitions against real PostgreSQL.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WorkflowRunRepositoryIntegrationTest extends BaseTestcontainersTest {

    @Autowired
    private WorkflowRunRepository workflowRunRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    private WorkflowEntity testWorkflow;

    @BeforeEach
    void setUp() {
        workflowRunRepository.deleteAll();
        workflowRepository.deleteAll();

        // Create a test workflow
        testWorkflow = new WorkflowEntity();
        testWorkflow.setName("test-workflow");
        testWorkflow.setDescription("Test workflow for runs");
        testWorkflow = workflowRepository.save(testWorkflow);
    }

    @Test
    void shouldSaveAndFindWorkflowRun() {
        // Given
        WorkflowRun run = createWorkflowRun(testWorkflow, WorkflowRun.RunStatus.RUNNING);

        // When
        WorkflowRun saved = workflowRunRepository.save(run);

        // Then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(WorkflowRun.RunStatus.RUNNING);
        assertThat(saved.getStartedAt()).isNotNull();
        assertThat(saved.getFinishedAt()).isNull();
    }

    @Test
    void shouldFindWorkflowRunById() {
        // Given
        WorkflowRun run = createWorkflowRun(testWorkflow, WorkflowRun.RunStatus.RUNNING);
        WorkflowRun saved = workflowRunRepository.save(run);

        // When
        Optional<WorkflowRun> found = workflowRunRepository.findById(saved.getId());

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getWorkflow().getId()).isEqualTo(testWorkflow.getId());
    }

    @Test
    void shouldUpdateRunStatusToCompleted() {
        // Given
        WorkflowRun run = createWorkflowRun(testWorkflow, WorkflowRun.RunStatus.RUNNING);
        WorkflowRun saved = workflowRunRepository.save(run);

        // When
        saved.setStatus(WorkflowRun.RunStatus.COMPLETED);
        saved.setFinishedAt(LocalDateTime.now());
        WorkflowRun updated = workflowRunRepository.save(saved);

        // Then
        assertThat(updated.getStatus()).isEqualTo(WorkflowRun.RunStatus.COMPLETED);
        assertThat(updated.getFinishedAt()).isNotNull();
    }

    @Test
    void shouldUpdateRunStatusToFailed() {
        // Given
        WorkflowRun run = createWorkflowRun(testWorkflow, WorkflowRun.RunStatus.RUNNING);
        WorkflowRun saved = workflowRunRepository.save(run);

        // When
        saved.setStatus(WorkflowRun.RunStatus.FAILED);
        saved.setFinishedAt(LocalDateTime.now());
        WorkflowRun updated = workflowRunRepository.save(saved);

        // Then
        assertThat(updated.getStatus()).isEqualTo(WorkflowRun.RunStatus.FAILED);
        assertThat(updated.getFinishedAt()).isNotNull();
    }

    @Test
    void shouldFindAllRunsForWorkflow() {
        // Given - Create multiple runs for the same workflow
        workflowRunRepository.save(createWorkflowRun(testWorkflow, WorkflowRun.RunStatus.COMPLETED));
        workflowRunRepository.save(createWorkflowRun(testWorkflow, WorkflowRun.RunStatus.RUNNING));
        workflowRunRepository.save(createWorkflowRun(testWorkflow, WorkflowRun.RunStatus.FAILED));

        // When
        List<WorkflowRun> runs = workflowRunRepository.findAll();

        // Then
        assertThat(runs).hasSize(3);
    }

    @Test
    void shouldDeleteWorkflowRun() {
        // Given
        WorkflowRun run = createWorkflowRun(testWorkflow, WorkflowRun.RunStatus.RUNNING);
        WorkflowRun saved = workflowRunRepository.save(run);

        // When
        workflowRunRepository.deleteById(saved.getId());

        // Then
        Optional<WorkflowRun> found = workflowRunRepository.findById(saved.getId());
        assertThat(found).isEmpty();
    }

    @Test
    void shouldMaintainRelationshipWithWorkflow() {
        // Given
        WorkflowRun run = createWorkflowRun(testWorkflow, WorkflowRun.RunStatus.RUNNING);
        WorkflowRun saved = workflowRunRepository.save(run);

        // When
        WorkflowRun found = workflowRunRepository.findById(saved.getId()).orElseThrow();

        // Then - Verify lazy loading works
        assertThat(found.getWorkflow()).isNotNull();
        assertThat(found.getWorkflow().getName()).isEqualTo("test-workflow");
    }

    @Test
    void shouldCreateMultipleRunsForSameWorkflow() {
        // Given
        WorkflowRun run1 = createWorkflowRun(testWorkflow, WorkflowRun.RunStatus.COMPLETED);
        WorkflowRun run2 = createWorkflowRun(testWorkflow, WorkflowRun.RunStatus.RUNNING);
        WorkflowRun run3 = createWorkflowRun(testWorkflow, WorkflowRun.RunStatus.FAILED);

        // When
        workflowRunRepository.saveAll(List.of(run1, run2, run3));

        // Then
        long count = workflowRunRepository.count();
        assertThat(count).isEqualTo(3);
    }

    @Test
    void shouldTrackStartedAtTime() {
        // Given
        LocalDateTime beforeSave = LocalDateTime.now().minusSeconds(1);
        WorkflowRun run = createWorkflowRun(testWorkflow, WorkflowRun.RunStatus.RUNNING);

        // When
        WorkflowRun saved = workflowRunRepository.save(run);
        LocalDateTime afterSave = LocalDateTime.now().plusSeconds(1);

        // Then
        assertThat(saved.getStartedAt()).isAfter(beforeSave);
        assertThat(saved.getStartedAt()).isBefore(afterSave);
    }

    @Test
    void shouldHandleDifferentWorkflows() {
        // Given - Create another workflow
        WorkflowEntity anotherWorkflow = new WorkflowEntity();
        anotherWorkflow.setName("another-workflow");
        anotherWorkflow.setDescription("Another test workflow");
        anotherWorkflow = workflowRepository.save(anotherWorkflow);

        // When - Create runs for both workflows
        workflowRunRepository.save(createWorkflowRun(testWorkflow, WorkflowRun.RunStatus.RUNNING));
        workflowRunRepository.save(createWorkflowRun(anotherWorkflow, WorkflowRun.RunStatus.RUNNING));

        // Then
        List<WorkflowRun> allRuns = workflowRunRepository.findAll();
        assertThat(allRuns).hasSize(2);
        assertThat(allRuns)
                .extracting(run -> run.getWorkflow().getName())
                .containsExactlyInAnyOrder("test-workflow", "another-workflow");
    }

    // Helper method
    private WorkflowRun createWorkflowRun(WorkflowEntity workflow, WorkflowRun.RunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setWorkflow(workflow);
        run.setStatus(status);
        run.setStartedAt(LocalDateTime.now());
        if (status == WorkflowRun.RunStatus.COMPLETED || status == WorkflowRun.RunStatus.FAILED) {
            run.setFinishedAt(LocalDateTime.now());
        }
        return run;
    }
}

