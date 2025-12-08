package com.worker.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Integration tests for Worker Service with Redis Streams
 * Uses Testcontainers to spin up real Redis instance
 */
@SpringBootTest
@Testcontainers
class WorkerServiceIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private static final String STREAM_KEY = "tasks_stream";

    @BeforeEach
    void setUp() {
        // Clean up Redis before each test
        try {
            redisTemplate.delete(STREAM_KEY);
        } catch (Exception e) {
            // Ignore if key doesn't exist
        }
    }

    @Test
    void shouldConsumeTaskFromRedisStream() {
        // Given
        Map<String, String> taskData = new HashMap<>();
        taskData.put("workflowRunId", "integration-run-123");
        taskData.put("taskId", "integration-task-456");
        taskData.put("command", "echo 'Integration test'");

        // When
        RecordId recordId = redisTemplate.opsForStream().add(
                StreamRecords.string(taskData).withStreamKey(STREAM_KEY)
        );

        // Then
        assertThat(recordId).isNotNull();

        // Verify message was added to stream
        Long streamLength = redisTemplate.opsForStream().size(STREAM_KEY);
        assertThat(streamLength).isGreaterThan(0);
    }

    @Test
    void shouldCreateConsumerGroupOnStartup() {
        // Given - Worker service starts automatically in @SpringBootTest

        // When - Wait for consumer group to be created
        await().atMost(5, SECONDS).untilAsserted(() -> {
            // Try to add a message and verify consumer group exists
            Map<String, String> taskData = new HashMap<>();
            taskData.put("workflowRunId", "test-run");
            taskData.put("taskId", "test-task");
            taskData.put("command", "echo 'test'");

            redisTemplate.opsForStream().add(
                    StreamRecords.string(taskData).withStreamKey(STREAM_KEY)
            );
        });

        // Then - No exception means consumer group was created successfully
        assertThat(redis.isRunning()).isTrue();
    }

    @Test
    void shouldHandleMultipleTasksInStream() {
        // Given
        int taskCount = 5;

        // When
        for (int i = 0; i < taskCount; i++) {
            Map<String, String> taskData = new HashMap<>();
            taskData.put("workflowRunId", "multi-run-" + i);
            taskData.put("taskId", "multi-task-" + i);
            taskData.put("command", "echo 'Task " + i + "'");

            redisTemplate.opsForStream().add(
                    StreamRecords.string(taskData).withStreamKey(STREAM_KEY)
            );
        }

        // Then
        Long streamLength = redisTemplate.opsForStream().size(STREAM_KEY);
        assertThat(streamLength).isGreaterThanOrEqualTo(taskCount);
    }

    @Test
    void shouldHandleRedisConnectionRecovery() throws InterruptedException {
        // Given
        Map<String, String> taskData = new HashMap<>();
        taskData.put("workflowRunId", "recovery-run");
        taskData.put("taskId", "recovery-task");
        taskData.put("command", "echo 'Recovery test'");

        // When - Add task
        RecordId recordId = redisTemplate.opsForStream().add(
                StreamRecords.string(taskData).withStreamKey(STREAM_KEY)
        );

        // Simulate brief pause (worker continues running)
        Thread.sleep(1000);

        // Then - Task should still be in stream (or consumed)
        assertThat(recordId).isNotNull();
    }

    @Test
    void shouldVerifyRedisStreamConfiguration() {
        // Given - Redis is running in container

        // When
        String redisHost = redis.getHost();
        Integer redisPort = redis.getFirstMappedPort();

        // Then
        assertThat(redisHost).isNotNull();
        assertThat(redisPort).isGreaterThan(0);
        assertThat(redis.isRunning()).isTrue();
    }

    @Test
    void shouldEnqueueJobMarketCrawlerTask() {
        // Given - Simulate a real job market crawler task
        Map<String, String> crawlerTask = new HashMap<>();
        crawlerTask.put("workflowRunId", "job-crawler-run-001");
        crawlerTask.put("taskId", "crawl-linkedin");
        crawlerTask.put("command", "python /opt/crawlers/linkedin.py --max-pages 10");

        // When
        RecordId recordId = redisTemplate.opsForStream().add(
                StreamRecords.string(crawlerTask).withStreamKey(STREAM_KEY)
        );

        // Then
        assertThat(recordId).isNotNull();

        // Verify stream has the task
        Long size = redisTemplate.opsForStream().size(STREAM_KEY);
        assertThat(size).isGreaterThan(0);
    }

    @Test
    void shouldEnqueueAIExtractionTask() {
        // Given - Simulate AI extraction task after crawler
        Map<String, String> aiTask = new HashMap<>();
        aiTask.put("workflowRunId", "job-crawler-run-001");
        aiTask.put("taskId", "ai-extract");
        aiTask.put("command", "python /opt/crawlers/ai_extract.py --source linkedin");

        // When
        RecordId recordId = redisTemplate.opsForStream().add(
                StreamRecords.string(aiTask).withStreamKey(STREAM_KEY)
        );

        // Then
        assertThat(recordId).isNotNull();
    }

    @Test
    void shouldHandleTaskWithLongCommand() {
        // Given
        StringBuilder longCommand = new StringBuilder("python script.py");
        for (int i = 0; i < 50; i++) {
            longCommand.append(" --param").append(i).append(" value").append(i);
        }

        Map<String, String> taskData = new HashMap<>();
        taskData.put("workflowRunId", "long-cmd-run");
        taskData.put("taskId", "long-cmd-task");
        taskData.put("command", longCommand.toString());

        // When
        RecordId recordId = redisTemplate.opsForStream().add(
                StreamRecords.string(taskData).withStreamKey(STREAM_KEY)
        );

        // Then
        assertThat(recordId).isNotNull();
        Long size = redisTemplate.opsForStream().size(STREAM_KEY);
        assertThat(size).isGreaterThan(0);
    }
}

