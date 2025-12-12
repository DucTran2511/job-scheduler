package com.api.controller;

import com.api.orchestrator.WorkflowOrchestrator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkflowController.class)
@DisplayName("API Tests - Real Task Scenarios (SHELL)")
class RealTaskApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowOrchestrator orchestrator;

    // ========================================================================
    // Job Market Crawling Scenarios (using SHELL to run Python scripts)
    // ========================================================================

    @Nested
    @DisplayName("Job Market Crawling Workflows")
    class JobMarketCrawlingTests {

        @Test
        @DisplayName("Should start LinkedIn job crawling workflow")
        void shouldStartLinkedInCrawlingWorkflow() throws Exception {
            String linkedInWorkflow = """
                name: linkedin-job-crawler
                description: Crawl job listings from LinkedIn
                tasks:
                  - id: fetch_linkedin_pages
                    name: Fetch LinkedIn Job Pages
                    command: python scripts/crawl_linkedin.py --pages 10
                    taskType: SHELL
                    timeoutSeconds: 300
                    maxRetries: 3
                  
                  - id: parse_html
                    name: Parse HTML Content
                    command: python scripts/parse_jobs.py --source linkedin
                    taskType: SHELL
                    depends_on:
                      - fetch_linkedin_pages
                    timeoutSeconds: 120
                  
                  - id: extract_with_ai
                    name: Extract Job Info with AI
                    command: python scripts/ai_extract.py --model gpt-4
                    taskType: SHELL
                    depends_on:
                      - parse_html
                    timeoutSeconds: 600
                  
                  - id: save_to_database
                    name: Save to Database
                    command: python scripts/save_jobs.py --db postgres
                    taskType: SHELL
                    depends_on:
                      - extract_with_ai
                """;

            when(orchestrator.startWorkflow(any())).thenReturn("linkedin-run-001");

            mockMvc.perform(post("/api/workflows/start")
                            .contentType("text/plain")
                            .content(linkedInWorkflow))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.workflowRunId").value("linkedin-run-001"));

            verify(orchestrator).startWorkflow(linkedInWorkflow);
        }

        @Test
        @DisplayName("Should start TopCV job crawling workflow")
        void shouldStartTopCVCrawlingWorkflow() throws Exception {
            String topCVWorkflow = """
                name: topcv-job-crawler
                description: Crawl job listings from TopCV Vietnam
                tasks:
                  - id: crawl_topcv
                    name: Crawl TopCV Pages
                    command: |
                      curl -s "https://www.topcv.vn/tim-viec-lam-it" | \
                      python scripts/save_html.py --output /tmp/topcv
                    taskType: SHELL
                    timeoutSeconds: 180
                    maxRetries: 5
                  
                  - id: process_topcv
                    name: Process TopCV Data
                    command: python scripts/process_topcv.py --input /tmp/topcv
                    taskType: SHELL
                    depends_on:
                      - crawl_topcv
                """;

            when(orchestrator.startWorkflow(any())).thenReturn("topcv-run-001");

            mockMvc.perform(post("/api/workflows/start")
                            .contentType("text/plain")
                            .content(topCVWorkflow))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.workflowRunId").value("topcv-run-001"));
        }

        @Test
        @DisplayName("Should start ITviec job crawling workflow")
        void shouldStartITviecCrawlingWorkflow() throws Exception {
            String itviecWorkflow = """
                name: itviec-job-crawler
                description: Crawl IT job listings from ITviec
                tasks:
                  - id: crawl_itviec
                    name: Crawl ITviec Pages
                    command: python scripts/crawl_itviec.py --category backend
                    taskType: SHELL
                    timeoutSeconds: 300
                  
                  - id: ai_extraction
                    name: AI Job Extraction
                    command: |
                      python scripts/ai_extract.py \
                        --input /tmp/itviec_html \
                        --model gpt-4 \
                        --extract salary,skills,requirements
                    taskType: SHELL
                    depends_on:
                      - crawl_itviec
                    timeoutSeconds: 600
                """;

            when(orchestrator.startWorkflow(any())).thenReturn("itviec-run-001");

            mockMvc.perform(post("/api/workflows/start")
                            .contentType("text/plain")
                            .content(itviecWorkflow))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.workflowRunId").value("itviec-run-001"));
        }

        @Test
        @DisplayName("Should start multi-source parallel crawling workflow")
        void shouldStartMultiSourceCrawlingWorkflow() throws Exception {
            String multiSourceWorkflow = """
                name: multi-source-job-crawler
                description: Crawl jobs from multiple sources in parallel
                tasks:
                  - id: crawl_linkedin
                    name: Crawl LinkedIn
                    command: python scripts/crawl.py --source linkedin
                    taskType: SHELL
                    timeoutSeconds: 300
                  
                  - id: crawl_topcv
                    name: Crawl TopCV
                    command: python scripts/crawl.py --source topcv
                    taskType: SHELL
                    timeoutSeconds: 300
                  
                  - id: crawl_itviec
                    name: Crawl ITviec
                    command: python scripts/crawl.py --source itviec
                    taskType: SHELL
                    timeoutSeconds: 300
                  
                  - id: merge_results
                    name: Merge All Results
                    command: python scripts/merge_jobs.py --sources linkedin,topcv,itviec
                    taskType: SHELL
                    depends_on:
                      - crawl_linkedin
                      - crawl_topcv
                      - crawl_itviec
                    timeoutSeconds: 120
                  
                  - id: deduplicate
                    name: Remove Duplicates
                    command: python scripts/dedupe.py --similarity 0.85
                    taskType: SHELL
                    depends_on:
                      - merge_results
                  
                  - id: store_final
                    name: Store Final Results
                    command: python scripts/store.py --db postgres --table jobs
                    taskType: SHELL
                    depends_on:
                      - deduplicate
                """;

            when(orchestrator.startWorkflow(any())).thenReturn("multi-crawl-001");

            mockMvc.perform(post("/api/workflows/start")
                            .contentType("text/plain")
                            .content(multiSourceWorkflow))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.workflowRunId").value("multi-crawl-001"));
        }
    }

    // ========================================================================
    // Task Callback Scenarios
    // ========================================================================

    @Nested
    @DisplayName("Task Callback - Real Scenarios")
    class TaskCallbackTests {

        @Test
        @DisplayName("Should handle successful crawl task completion")
        void shouldHandleSuccessfulCrawlTask() throws Exception {
            String callback = """
                {
                    "workflowRunId": "linkedin-run-001",
                    "taskId": "fetch_linkedin_pages",
                    "success": true,
                    "lastError": null
                }
                """;

            mockMvc.perform(post("/api/workflows/task/callback")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(callback))
                    .andExpect(status().isOk());

            verify(orchestrator).onTaskCompleted("linkedin-run-001", "fetch_linkedin_pages", true, null);
        }

        @Test
        @DisplayName("Should handle crawl task failure with rate limiting error")
        void shouldHandleCrawlRateLimitingError() throws Exception {
            String callback = """
                {
                    "workflowRunId": "linkedin-run-001",
                    "taskId": "fetch_linkedin_pages",
                    "success": false,
                    "lastError": "HTTP 429 Too Many Requests - Rate limited by LinkedIn"
                }
                """;

            mockMvc.perform(post("/api/workflows/task/callback")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(callback))
                    .andExpect(status().isOk());

            verify(orchestrator).onTaskCompleted(
                    "linkedin-run-001",
                    "fetch_linkedin_pages",
                    false,
                    "HTTP 429 Too Many Requests - Rate limited by LinkedIn"
            );
        }

        @Test
        @DisplayName("Should handle AI extraction task timeout")
        void shouldHandleAIExtractionTimeout() throws Exception {
            String callback = """
                {
                    "workflowRunId": "itviec-run-001",
                    "taskId": "ai_extraction",
                    "success": false,
                    "lastError": "Task timeout after 600 seconds - OpenAI API slow response"
                }
                """;

            mockMvc.perform(post("/api/workflows/task/callback")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(callback))
                    .andExpect(status().isOk());

            verify(orchestrator).onTaskCompleted(
                    eq("itviec-run-001"),
                    eq("ai_extraction"),
                    eq(false),
                    eq("Task timeout after 600 seconds - OpenAI API slow response")
            );
        }

        @Test
        @DisplayName("Should handle database save task completion")
        void shouldHandleDatabaseSaveCompletion() throws Exception {
            mockMvc.perform(post("/api/workflows/linkedin-run-001/tasks/save_to_database/complete")
                            .param("success", "true"))
                    .andExpect(status().isOk());

            verify(orchestrator).onTaskCompleted("linkedin-run-001", "save_to_database", true, null);
        }

        @Test
        @DisplayName("Should handle database connection failure")
        void shouldHandleDatabaseConnectionFailure() throws Exception {
            mockMvc.perform(post("/api/workflows/linkedin-run-001/tasks/save_to_database/complete")
                            .param("success", "false")
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("FATAL: connection to server at 'localhost:5432' failed: Connection refused"))
                    .andExpect(status().isOk());

            verify(orchestrator).onTaskCompleted(
                    "linkedin-run-001",
                    "save_to_database",
                    false,
                    "FATAL: connection to server at 'localhost:5432' failed: Connection refused"
            );
        }
    }

    // ========================================================================
    // Database Backup Workflow Scenarios
    // ========================================================================

    @Nested
    @DisplayName("Database Backup Workflows")
    class DatabaseBackupTests {

        @Test
        @DisplayName("Should start PostgreSQL backup workflow")
        void shouldStartPostgresBackupWorkflow() throws Exception {
            String backupWorkflow = """
                name: daily-postgres-backup
                description: Daily PostgreSQL database backup
                tasks:
                  - id: stop_connections
                    name: Stop Active Connections
                    command: |
                      psql -U admin -c "SELECT pg_terminate_backend(pid) 
                      FROM pg_stat_activity WHERE datname='production' 
                      AND pid <> pg_backend_pid();"
                    taskType: SHELL
                    timeoutSeconds: 30
                  
                  - id: create_backup
                    name: Create Database Dump
                    command: |
                      pg_dump -U admin -h localhost -Fc production > \
                      /backups/production_$(date +%Y%m%d_%H%M%S).dump
                    taskType: SHELL
                    depends_on:
                      - stop_connections
                    timeoutSeconds: 3600
                    maxRetries: 2
                  
                  - id: compress_backup
                    name: Compress Backup
                    command: gzip -9 /backups/production_*.dump
                    taskType: SHELL
                    depends_on:
                      - create_backup
                  
                  - id: upload_s3
                    name: Upload to S3
                    command: |
                      aws s3 cp /backups/production_*.dump.gz \
                      s3://company-backups/postgres/$(date +%Y/%m/%d)/
                    taskType: SHELL
                    depends_on:
                      - compress_backup
                    timeoutSeconds: 1800
                  
                  - id: cleanup_local
                    name: Cleanup Local Files
                    command: find /backups -name "*.dump.gz" -mtime +7 -delete
                    taskType: SHELL
                    depends_on:
                      - upload_s3
                  
                  - id: notify_slack
                    name: Send Slack Notification
                    command: |
                      curl -X POST -H 'Content-type: application/json' \
                      --data '{"text":"✅ Daily backup completed successfully"}' \
                      $SLACK_WEBHOOK_URL
                    taskType: SHELL
                    depends_on:
                      - upload_s3
                """;

            when(orchestrator.startWorkflow(any())).thenReturn("backup-run-001");

            mockMvc.perform(post("/api/workflows/start")
                            .contentType("text/plain")
                            .content(backupWorkflow))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.workflowRunId").value("backup-run-001"));
        }

        @Test
        @DisplayName("Should handle backup task with disk space error")
        void shouldHandleBackupDiskSpaceError() throws Exception {
            String callback = """
                {
                    "workflowRunId": "backup-run-001",
                    "taskId": "create_backup",
                    "success": false,
                    "lastError": "pg_dump: error: could not write to output file: No space left on device"
                }
                """;

            mockMvc.perform(post("/api/workflows/task/callback")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(callback))
                    .andExpect(status().isOk());

            verify(orchestrator).onTaskCompleted(
                    "backup-run-001",
                    "create_backup",
                    false,
                    "pg_dump: error: could not write to output file: No space left on device"
            );
        }
    }

    // ========================================================================
    // Error Handling Scenarios
    // ========================================================================

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle malformed YAML workflow")
        void shouldHandleMalformedYAML() throws Exception {
            String malformedYaml = """
                name: broken-workflow
                tasks:
                  - id: task1
                    command: echo test
                    depends_on:
                      - non_existent_task
                """;

            when(orchestrator.startWorkflow(any()))
                    .thenThrow(new RuntimeException("Dependency 'non_existent_task' not found for task 'task1'"));

            mockMvc.perform(post("/api/workflows/start")
                            .contentType("text/plain")
                            .content(malformedYaml))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("parse_error"))
                    .andExpect(jsonPath("$.message").value("Dependency 'non_existent_task' not found for task 'task1'"));
        }

        @Test
        @DisplayName("Should handle cyclic dependency error")
        void shouldHandleCyclicDependencyError() throws Exception {
            String cyclicWorkflow = """
                name: cyclic-workflow
                tasks:
                  - id: task1
                    command: echo 1
                    depends_on:
                      - task2
                  - id: task2
                    command: echo 2
                    depends_on:
                      - task1
                """;

            when(orchestrator.startWorkflow(any()))
                    .thenThrow(new RuntimeException("Cyclic dependency detected: task1 -> task2 -> task1"));

            mockMvc.perform(post("/api/workflows/start")
                            .contentType("text/plain")
                            .content(cyclicWorkflow))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("parse_error"))
                    .andExpect(jsonPath("$.message").value("Cyclic dependency detected: task1 -> task2 -> task1"));
        }

        @Test
        @DisplayName("Should handle empty workflow")
        void shouldHandleEmptyWorkflow() throws Exception {
            String emptyWorkflow = """
                name: empty-workflow
                tasks: []
                """;

            when(orchestrator.startWorkflow(any()))
                    .thenThrow(new RuntimeException("Workflow must contain at least one task"));

            mockMvc.perform(post("/api/workflows/start")
                            .contentType("text/plain")
                            .content(emptyWorkflow))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("parse_error"))
                    .andExpect(jsonPath("$.message").value("Workflow must contain at least one task"));
        }

        @Test
        @DisplayName("Should handle command not found error")
        void shouldHandleCommandNotFoundError() throws Exception {
            String callback = """
                {
                    "workflowRunId": "run-001",
                    "taskId": "task1",
                    "success": false,
                    "lastError": "bash: line 1: unknown_command: command not found"
                }
                """;

            mockMvc.perform(post("/api/workflows/task/callback")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(callback))
                    .andExpect(status().isOk());

            verify(orchestrator).onTaskCompleted(
                    eq("run-001"),
                    eq("task1"),
                    eq(false),
                    eq("bash: line 1: unknown_command: command not found")
            );
        }

        @Test
        @DisplayName("Should handle permission denied error")
        void shouldHandlePermissionDeniedError() throws Exception {
            String callback = """
                {
                    "workflowRunId": "run-001",
                    "taskId": "task1",
                    "success": false,
                    "lastError": "bash: /scripts/run.sh: Permission denied"
                }
                """;

            mockMvc.perform(post("/api/workflows/task/callback")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(callback))
                    .andExpect(status().isOk());

            verify(orchestrator).onTaskCompleted(
                    eq("run-001"),
                    eq("task1"),
                    eq(false),
                    eq("bash: /scripts/run.sh: Permission denied")
            );
        }
    }
}
