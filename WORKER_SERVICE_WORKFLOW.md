# Worker-Service Workflow Documentation

## Overview

The Worker-Service is a standalone Spring Boot application that consumes tasks from Redis Streams, executes them, and reports results back to the API-Service (Orchestrator).

---

## Architecture Components

### 1. **TaskStreamConsumer** (Main Consumer Loop)
- **Location**: `com.worker.service.TaskStreamConsumer`
- **Purpose**: Continuously polls Redis Stream for new tasks
- **Lifecycle**: Starts automatically on application startup via `@PostConstruct`

### 2. **ExecutorFactory** (Task Routing)
- **Location**: `com.worker.executor.ExecutorFactory`
- **Purpose**: Routes tasks to appropriate executors based on task type
- **Supported Types**: SHELL (currently), HTTP, PYTHON, DOCKER (planned)

### 3. **TaskExecutor Implementations** (Task Execution)
- **ShellTaskExecutor**: Executes bash commands
- **Location**: `com.worker.executor.impl.ShellTaskExecutor`

### 4. **OrchestratorCallbackService** (Result Reporting)
- **Location**: `com.worker.service.OrchestratorCallbackService`
- **Purpose**: Reports task completion back to orchestrator via HTTP callback

---

## Complete Workflow (Step-by-Step)

```
┌─────────────────────────────────────────────────────────────────┐
│                    WORKER-SERVICE WORKFLOW                       │
└─────────────────────────────────────────────────────────────────┘

1. APPLICATION STARTUP
   ├─> Spring Boot Application starts
   ├─> TaskStreamConsumer @PostConstruct triggered
   ├─> Consumer name generated: "worker-{UUID}" (if not configured)
   ├─> Consumer group ensured: "worker-group"
   └─> Background thread starts: consumeTasksLoop()

2. CONSUMER LOOP (Continuous)
   ├─> Poll Redis Stream: "tasks_stream"
   │   ├─> Read offset: Last consumed
   │   ├─> Block timeout: 2 seconds (configurable)
   │   └─> Consumer group: "worker-group"
   │
   ├─> Messages received? (Yes/No)
   │   └─> No → Sleep briefly → Loop back to step 2
   │   └─> Yes → Continue to step 3

3. MESSAGE PROCESSING (For each message)
   │
   ├─> Extract message data:
   │   ├─> workflowRunId
   │   ├─> taskId
   │   ├─> taskType (default: "SHELL")
   │   ├─> command (or config map)
   │   └─> timeout (optional)
   │
   ├─> Build TaskExecutionContext:
   │   ├─> Set workflowRunId, taskId, taskType
   │   ├─> Parse config (command, env, workingDir)
   │   └─> Set timeout if provided
   │
   └─> Continue to step 4

4. TASK EXECUTION
   │
   ├─> Get executor from ExecutorFactory
   │   └─> Match based on taskType (SHELL/HTTP/PYTHON/DOCKER)
   │
   ├─> Execute task via executor.execute(context)
   │   │
   │   └─> For SHELL executor:
   │       ├─> Create ProcessBuilder("bash", "-c", command)
   │       ├─> Set working directory (if provided)
   │       ├─> Set environment variables (if provided)
   │       ├─> Start process
   │       ├─> Capture stdout and stderr
   │       ├─> Wait for completion (with timeout if specified)
   │       └─> Build TaskExecutionResult
   │           ├─> success: true/false (based on exit code)
   │           ├─> output: stdout content
   │           ├─> errorOutput: stderr content
   │           ├─> exitCode: process exit code
   │           ├─> executionTimeMs: duration
   │           └─> errorMessage: (if failed)
   │
   └─> Continue to step 5

5. RESULT REPORTING
   │
   ├─> Update health metrics: WorkerHealthIndicator.recordTaskProcessed()
   │
   ├─> Report to orchestrator via HTTP callback
   │   ├─> URL: http://localhost:8080/api/workflows/task/callback
   │   ├─> Method: POST
   │   ├─> Payload:
   │   │   {
   │   │     "workflowRunId": "...",
   │   │     "taskId": "...",
   │   │     "success": true/false,
   │   │     "lastError": "..." (if failed)
   │   │   }
   │   │
   │   └─> Retry logic (up to 3 attempts):
   │       ├─> Attempt 1 → Fail → Wait 1s
   │       ├─> Attempt 2 → Fail → Wait 1s
   │       ├─> Attempt 3 → Fail → Log error
   │       └─> Success → Continue
   │
   └─> Continue to step 6

6. MESSAGE ACKNOWLEDGMENT
   │
   ├─> Acknowledge message in Redis Stream
   │   └─> redisTemplate.opsForStream().acknowledge(...)
   │
   ├─> Message removed from pending list
   │
   └─> Loop back to step 2 (next message)

7. ERROR HANDLING (at any step)
   │
   ├─> Log error with context
   ├─> Sleep for 1 second (avoid tight loop)
   └─> Continue processing next messages
```

---

## Configuration Properties

### Redis Stream Settings
```properties
# Stream key for tasks
worker.stream.key=tasks_stream

# Consumer group name (all workers in same group share load)
worker.consumer.group=worker-group

# Consumer name (auto-generated if empty: worker-{UUID})
worker.consumer.name=

# How long to block waiting for messages (seconds)
worker.poll.timeout-seconds=2

# Sleep duration after error (milliseconds)
worker.error.sleep-ms=1000
```

### Orchestrator Callback Settings
```properties
# Callback URL to report task completion
orchestrator.callback.url=http://localhost:8080/api/workflows/task/callback

# Number of retry attempts
orchestrator.callback.retries=3

# Delay between retries (milliseconds)
orchestrator.callback.retry-delay-ms=1000
```

### Executor Settings
```properties
# Enable/disable shell executor
executor.shell.enabled=true
```

---

## Data Models

### TaskExecutionContext
```java
{
  "workflowRunId": "run-123",
  "taskId": "task-456",
  "taskType": "SHELL",
  "config": {
    "command": "echo 'Hello World'",
    "env": {...},
    "workingDir": "/tmp"
  },
  "timeout": 30000,  // milliseconds
  "environment": {...},
  "workingDirectory": "/tmp"
}
```

### TaskExecutionResult
```java
{
  "success": true,
  "output": "Hello World\n",
  "errorOutput": "",
  "exitCode": 0,
  "errorMessage": null,
  "executionTimeMs": 123
}
```

---

## Redis Stream Message Format

### Message Published by API-Service
```json
{
  "workflowRunId": "run-abc123",
  "taskId": "task-1",
  "taskType": "SHELL",
  "command": "ls -la",
  "timeout": "30000",
  "config": {
    "command": "ls -la",
    "workingDir": "/tmp"
  }
}
```

### Backward Compatibility
- If `command` is at root level → Used directly
- If `config` map exists → Merged with root level
- Default `taskType` = "SHELL" if not specified

---

## Scaling Workers

### Horizontal Scaling
Workers automatically load-balance using Redis Consumer Groups:

1. **Same Consumer Group** → Share tasks (load balancing)
   ```yaml
   worker-1: consumer.group=worker-group
   worker-2: consumer.group=worker-group
   worker-3: consumer.group=worker-group
   # Tasks distributed across all 3 workers
   ```

2. **Different Consumer Groups** → All receive same tasks (broadcasting)
   ```yaml
   worker-1: consumer.group=group-A
   worker-2: consumer.group=group-B
   # Both receive ALL tasks independently
   ```

### Unique Consumer Names
Each worker instance needs a unique consumer name:
- Auto-generated: `worker-{UUID}` (recommended)
- Manual: Set via environment variable `WORKER_CONSUMER_NAME`

---

## Health Monitoring

### Actuator Endpoints
```bash
# Health check
GET http://localhost:8081/actuator/health

# Metrics
GET http://localhost:8081/actuator/metrics

# Prometheus metrics
GET http://localhost:8081/actuator/prometheus
```

### Custom Health Indicator
- **WorkerHealthIndicator**: Tracks task processing metrics
- **Metrics tracked**:
  - Last task processed timestamp
  - Total tasks processed
  - Current executor availability

---

## Error Handling & Resilience

### 1. Task Execution Errors
- **Process failures**: Exit code != 0 → Reported as failed
- **Timeouts**: Process killed after timeout → Reported as failed
- **Exceptions**: Caught and reported with error message

### 2. Callback Failures
- **Retry mechanism**: 3 attempts with 1s delay
- **Failures logged**: If all retries fail, error logged
- **Message still acknowledged**: Prevents re-processing

### 3. Stream Consumer Errors
- **Sleep on error**: 1 second delay to avoid tight loop
- **Continuous retry**: Loop continues after error
- **Message re-processing**: Failed messages stay in pending list until acknowledged

---

## Executor Types

### Current: SHELL Executor
```yaml
taskType: SHELL
config:
  command: "echo 'Hello' && sleep 2"
  workingDir: "/tmp"
  env:
    MY_VAR: "value"
```

### Planned Executors

#### HTTP Executor
```yaml
taskType: HTTP
config:
  url: "https://api.example.com/process"
  method: "POST"
  headers:
    Authorization: "Bearer token"
  body: "{...}"
  timeout: 5000
```

#### Python Executor
```yaml
taskType: PYTHON
config:
  script: "import sys; print('Hello from Python')"
  # OR
  scriptPath: "/path/to/script.py"
  pythonPath: "/usr/bin/python3"
```

#### Docker Executor
```yaml
taskType: DOCKER
config:
  image: "ubuntu:22.04"
  command: "bash -c 'echo Hello from Docker'"
  volumes:
    - "/host/path:/container/path"
  environment:
    MY_VAR: "value"
```

---

## Deployment

### Docker
```dockerfile
FROM eclipse-temurin:21-jdk
COPY target/worker-service-1.0.0.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

### Environment Variables
```bash
# Database
DB_HOST=postgres
DB_PORT=5432
DB_NAME=jobdb
DB_USER=jobuser
DB_PASSWORD=jobpass

# Redis
REDIS_HOST=redis
REDIS_PORT=6379

# Worker Configuration
WORKER_CONSUMER_GROUP=worker-group
WORKER_CONSUMER_NAME=worker-1
ORCHESTRATOR_CALLBACK_URL=http://api-service:8080/api/workflows/task/callback
```

### Docker Compose
```yaml
worker-service:
  build: ./worker-service
  environment:
    - DB_HOST=postgres
    - REDIS_HOST=redis
    - ORCHESTRATOR_CALLBACK_URL=http://api-service:8080/api/workflows/task/callback
  depends_on:
    - postgres
    - redis
    - api-service
```

---

## Logging

### Log Levels
```
INFO:  Task lifecycle events (received, started, completed)
DEBUG: Detailed execution steps, message acknowledgments
ERROR: Failures, exceptions, retry attempts
```

### Example Logs
```
🚀 Task stream consumer started: group=worker-group, consumer=worker-a1b2c3d4, stream=tasks_stream
👷 Worker received task: workflowRunId=run-123, taskId=task-1, type=SHELL
Executing task task-1 with SHELL executor
✅ Task task-1 completed successfully in 1234ms
✅ Successfully reported task task-1 completion to orchestrator
Acknowledged message: 1234567890-0
```

---

## Testing

### Unit Tests
- **ExecutorTests**: Test each executor independently
- **ContextBuilding**: Test message parsing logic

### Integration Tests (Testcontainers)
- **Redis Stream Integration**: Test message consumption
- **End-to-End**: Full workflow from stream to callback

### Manual Testing
```bash
# 1. Start services
docker-compose up

# 2. Submit a task via API-Service
curl -X POST http://localhost:8080/api/workflows/start \
  -H "Content-Type: text/plain" \
  -d 'name: test-workflow
tasks:
  - id: task-1
    type: SHELL
    command: "echo Hello from worker"'

# 3. Check worker logs
docker logs job-scheduler-worker-service-1 -f

# 4. Check task completion
curl http://localhost:8080/api/workflows/{runId}
```

---

## Performance Considerations

### Throughput
- **Parallel workers**: Scale horizontally for higher throughput
- **Poll timeout**: Lower = more responsive, higher CPU usage
- **Message batch size**: Current = 1, can be increased

### Latency
- **Poll timeout**: 2s default (affects max latency)
- **Callback retries**: 3 attempts × 1s = max 3s overhead on failure

### Resource Usage
- **Thread pool**: 1 consumer thread per worker instance
- **Memory**: Depends on task output size
- **Network**: HTTP callbacks for each task completion

---

## Common Issues & Solutions

### Issue: Worker not receiving tasks
**Check**:
1. Redis connection: `redis-cli PING`
2. Stream exists: `redis-cli XINFO STREAM tasks_stream`
3. Consumer group exists: `redis-cli XINFO GROUPS tasks_stream`
4. Worker logs: Check for connection errors

### Issue: Tasks stuck in pending
**Cause**: Worker crashed before acknowledging
**Solution**: 
```bash
# Check pending
redis-cli XPENDING tasks_stream worker-group

# Claim stuck messages (advanced)
redis-cli XCLAIM tasks_stream worker-group consumer-new 60000 {message-id}
```

### Issue: Callback failures
**Check**:
1. API-Service reachable: `curl http://api-service:8080/actuator/health`
2. Callback URL configured correctly
3. Network connectivity between services

---

## Future Enhancements

1. **Dead Letter Queue**: Move failed tasks to DLQ after max retries
2. **Task Priority**: Support priority queues in Redis
3. **Task Cancellation**: Support workflow/task cancellation
4. **Result Storage**: Store task outputs in database
5. **Metrics Dashboard**: Grafana dashboards for worker metrics
6. **Auto-scaling**: K8s HPA based on pending message count

---

## Summary

The Worker-Service implements a robust, scalable task execution system with:
- ✅ Redis Streams for reliable message queuing
- ✅ Consumer groups for load balancing
- ✅ Pluggable executor architecture
- ✅ Retry mechanisms for resilience
- ✅ Health monitoring and metrics
- ✅ Horizontal scalability
- ✅ Clean separation of concerns

**Next Steps**: Add HTTP, Python, and Docker executors for multi-executor support.

