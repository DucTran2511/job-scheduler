package com.api.testcontainers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Testcontainer integration tests for Redis Streams.
 * Tests job queue functionality for job market crawler (LinkedIn, TopCV, ITviec).
 */
@SpringBootTest
class RedisStreamsIntegrationTest extends BaseTestcontainersTest {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private StreamOperations<String, Object, Object> streamOps;

    private static final String STREAM_KEY = "job-crawler-tasks";
    private static final String CONSUMER_GROUP = "crawler-workers";

    @BeforeEach
    void setUp() {
        streamOps = redisTemplate.opsForStream();

        // Clean up streams and consumer groups
        try {
            redisTemplate.delete(STREAM_KEY);
        } catch (Exception e) {
            // Ignore if stream doesn't exist
        }
    }

    @Test
    void shouldAddMessageToStream() {
        // Given
        Map<String, String> message = Map.of(
                "taskId", "task1",
                "type", "linkedin-crawler",
                "url", "https://linkedin.com/jobs"
        );

        // When
        RecordId recordId = streamOps.add(STREAM_KEY, message);

        // Then
        assertThat(recordId).isNotNull();
        assertThat(recordId.getValue()).isNotEmpty();
    }

    @Test
    void shouldReadMessagesFromStream() {
        // Given
        streamOps.add(STREAM_KEY, Map.of("taskId", "task1", "type", "linkedin"));
        streamOps.add(STREAM_KEY, Map.of("taskId", "task2", "type", "topcv"));

        // When
        List<MapRecord<String, Object, Object>> messages = streamOps.range(
                STREAM_KEY, Range.unbounded()
        );

        // Then
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getValue().get("taskId")).isEqualTo("task1");
        assertThat(messages.get(1).getValue().get("taskId")).isEqualTo("task2");
    }

    @Test
    void shouldCreateConsumerGroup() {
        // Given
        streamOps.add(STREAM_KEY, Map.of("init", "true"));

        // When
        String result = streamOps.createGroup(STREAM_KEY, CONSUMER_GROUP);

        // Then
        assertThat(result).isEqualTo("OK");
    }

    @Test
    void shouldReadWithConsumerGroup() {
        // Given
        streamOps.add(STREAM_KEY, Map.of("taskId", "task1", "command", "crawl linkedin"));
        streamOps.createGroup(STREAM_KEY, ReadOffset.from("0"), CONSUMER_GROUP);

        // When - Read from last consumed position (which starts at 0 for new group)
        List<MapRecord<String, Object, Object>> messages = streamOps.read(
                Consumer.from(CONSUMER_GROUP, "worker-1"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(STREAM_KEY, ReadOffset.from(">"))
        );

        // Then
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getValue().get("taskId")).isEqualTo("task1");
    }

    @Test
    void shouldAcknowledgeMessage() {
        // Given
        RecordId recordId = streamOps.add(STREAM_KEY, Map.of("taskId", "task1"));
        streamOps.createGroup(STREAM_KEY, ReadOffset.from("0"), CONSUMER_GROUP);

        List<MapRecord<String, Object, Object>> messages = streamOps.read(
                Consumer.from(CONSUMER_GROUP, "worker-1"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(STREAM_KEY, ReadOffset.from(">"))
        );

        // When
        Long ackCount = streamOps.acknowledge(STREAM_KEY, CONSUMER_GROUP, messages.get(0).getId());

        // Then
        assertThat(ackCount).isEqualTo(1L);
    }

    @Test
    void shouldTrackPendingMessages() {
        // Given
        streamOps.add(STREAM_KEY, Map.of("taskId", "task1"));
        streamOps.add(STREAM_KEY, Map.of("taskId", "task2"));
        streamOps.createGroup(STREAM_KEY, ReadOffset.from("0"), CONSUMER_GROUP);

        // Read without acknowledging
        streamOps.read(
                Consumer.from(CONSUMER_GROUP, "worker-1"),
                StreamReadOptions.empty().count(2),
                StreamOffset.create(STREAM_KEY, ReadOffset.from(">"))
        );

        // When
        PendingMessagesSummary pending = streamOps.pending(STREAM_KEY, CONSUMER_GROUP);

        // Then
        assertThat(pending.getTotalPendingMessages()).isEqualTo(2L);
        assertThat(pending.getGroupName()).isEqualTo(CONSUMER_GROUP);
    }

    @Test
    void shouldHandleMultipleConsumers() {
        // Given
        streamOps.add(STREAM_KEY, Map.of("taskId", "task1"));
        streamOps.add(STREAM_KEY, Map.of("taskId", "task2"));
        streamOps.createGroup(STREAM_KEY, ReadOffset.from("0"), CONSUMER_GROUP);

        // When - Different consumers read messages
        List<MapRecord<String, Object, Object>> worker1Messages = streamOps.read(
                Consumer.from(CONSUMER_GROUP, "worker-1"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(STREAM_KEY, ReadOffset.from(">"))
        );

        List<MapRecord<String, Object, Object>> worker2Messages = streamOps.read(
                Consumer.from(CONSUMER_GROUP, "worker-2"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(STREAM_KEY, ReadOffset.from(">"))
        );

        // Then
        assertThat(worker1Messages).hasSize(1);
        assertThat(worker2Messages).hasSize(1);
        assertThat(worker1Messages.get(0).getValue().get("taskId"))
                .isNotEqualTo(worker2Messages.get(0).getValue().get("taskId"));
    }

    @Test
    void shouldSimulateJobCrawlerQueue() {
        // Given - Add crawler tasks for different job sites
        Map<String, String> linkedInJob = new HashMap<>();
        linkedInJob.put("taskId", "task-linkedin-1");
        linkedInJob.put("type", "linkedin-crawler");
        linkedInJob.put("url", "https://www.linkedin.com/jobs/search");
        linkedInJob.put("workflowRunId", "run-123");

        Map<String, String> topCVJob = new HashMap<>();
        topCVJob.put("taskId", "task-topcv-1");
        topCVJob.put("type", "topcv-crawler");
        topCVJob.put("url", "https://www.topcv.vn/viec-lam-it");
        topCVJob.put("workflowRunId", "run-123");

        Map<String, String> itViecJob = new HashMap<>();
        itViecJob.put("taskId", "task-itviec-1");
        itViecJob.put("type", "itviec-crawler");
        itViecJob.put("url", "https://itviec.com/it-jobs");
        itViecJob.put("workflowRunId", "run-123");

        // When - Add all tasks to stream
        streamOps.add(STREAM_KEY, linkedInJob);
        streamOps.add(STREAM_KEY, topCVJob);
        streamOps.add(STREAM_KEY, itViecJob);

        // Then
        Long streamLength = streamOps.size(STREAM_KEY);
        assertThat(streamLength).isEqualTo(3L);

        // Verify we can read all tasks
        List<MapRecord<String, Object, Object>> allTasks = streamOps.range(
                STREAM_KEY, Range.unbounded()
        );
        assertThat(allTasks).hasSize(3);
    }

    @Test
    void shouldProcessTasksWithWorkers() {
        // Given - Setup crawler tasks
        streamOps.add(STREAM_KEY, Map.of(
                "taskId", "linkedin-1",
                "type", "linkedin-crawler",
                "command", "python crawl_linkedin.py"
        ));
        streamOps.add(STREAM_KEY, Map.of(
                "taskId", "topcv-1",
                "type", "topcv-crawler",
                "command", "python crawl_topcv.py"
        ));

        streamOps.createGroup(STREAM_KEY, ReadOffset.from("0"), CONSUMER_GROUP);

        // When - Worker processes a task
        List<MapRecord<String, Object, Object>> messages = streamOps.read(
                Consumer.from(CONSUMER_GROUP, "crawler-worker-1"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(STREAM_KEY, ReadOffset.from(">"))
        );

        // Simulate task completion
        MapRecord<String, Object, Object> task = messages.get(0);
        RecordId taskRecordId = task.getId();

        // Acknowledge after processing
        Long ackCount = streamOps.acknowledge(STREAM_KEY, CONSUMER_GROUP, taskRecordId);

        // Then
        assertThat(messages).hasSize(1);
        assertThat(ackCount).isEqualTo(1L);
        assertThat(task.getValue().get("type")).isIn("linkedin-crawler", "topcv-crawler");
    }

    @Test
    void shouldHandleTaskRetry() {
        // Given - Add a task that will fail
        RecordId recordId = streamOps.add(STREAM_KEY, Map.of(
                "taskId", "retry-task",
                "retryCount", "0",
                "maxRetries", "3"
        ));
        streamOps.createGroup(STREAM_KEY, ReadOffset.from("0"), CONSUMER_GROUP);

        // When - Worker reads but doesn't acknowledge (simulating failure)
        List<MapRecord<String, Object, Object>> messages = streamOps.read(
                Consumer.from(CONSUMER_GROUP, "worker-1"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(STREAM_KEY, ReadOffset.from(">"))
        );

        // Then - Message is in pending state
        PendingMessagesSummary pending = streamOps.pending(STREAM_KEY, CONSUMER_GROUP);
        assertThat(pending.getTotalPendingMessages()).isEqualTo(1L);
    }

    @Test
    void shouldGetStreamInfo() {
        // Given
        streamOps.add(STREAM_KEY, Map.of("msg", "1"));
        streamOps.add(STREAM_KEY, Map.of("msg", "2"));
        streamOps.add(STREAM_KEY, Map.of("msg", "3"));

        // When
        Long size = streamOps.size(STREAM_KEY);

        // Then
        assertThat(size).isEqualTo(3L);
    }

    @Test
    void shouldSimulateCompleteWorkflow() {
        // Given - Job crawler workflow with multiple sites
        String workflowRunId = "workflow-run-456";

        // Add tasks for all job sites
        streamOps.add(STREAM_KEY, Map.of(
                "workflowRunId", workflowRunId,
                "taskId", "linkedin-crawler",
                "url", "https://linkedin.com/jobs",
                "aiModel", "gpt-4",
                "extractFields", "title,company,location,salary"
        ));

        streamOps.add(STREAM_KEY, Map.of(
                "workflowRunId", workflowRunId,
                "taskId", "topcv-crawler",
                "url", "https://topcv.vn",
                "aiModel", "gpt-4",
                "extractFields", "title,company,location,salary"
        ));

        streamOps.createGroup(STREAM_KEY, ReadOffset.from("0"), CONSUMER_GROUP);

        // When - Workers process tasks
        List<MapRecord<String, Object, Object>> worker1Tasks = streamOps.read(
                Consumer.from(CONSUMER_GROUP, "worker-1"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(STREAM_KEY, ReadOffset.from(">"))
        );

        List<MapRecord<String, Object, Object>> worker2Tasks = streamOps.read(
                Consumer.from(CONSUMER_GROUP, "worker-2"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(STREAM_KEY, ReadOffset.from(">"))
        );

        // Acknowledge both tasks
        streamOps.acknowledge(STREAM_KEY, CONSUMER_GROUP, worker1Tasks.get(0).getId());
        streamOps.acknowledge(STREAM_KEY, CONSUMER_GROUP, worker2Tasks.get(0).getId());

        // Then - All tasks processed
        PendingMessagesSummary pending = streamOps.pending(STREAM_KEY, CONSUMER_GROUP);
        assertThat(pending.getTotalPendingMessages()).isEqualTo(0L);
    }

    @Test
    void shouldHandleBlockingRead() {
        // Given
        streamOps.createGroup(STREAM_KEY, ReadOffset.from("0-0"), CONSUMER_GROUP);

        // Add message after a delay (simulating async producer)
        new Thread(() -> {
            try {
                Thread.sleep(500);
                streamOps.add(STREAM_KEY, Map.of("async", "message"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();

        // When - Blocking read with timeout
        List<MapRecord<String, Object, Object>> messages = streamOps.read(
                Consumer.from(CONSUMER_GROUP, "blocking-worker"),
                StreamReadOptions.empty().block(Duration.ofSeconds(2)),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
        );

        // Then
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(messages).isNotEmpty());
    }
}
