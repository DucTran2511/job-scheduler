# Worker Service Refactoring - Clean Architecture Documentation

## Overview

The worker-service has been completely refactored with a **clean, modular architecture** that follows best practices and implements the **Executor Pattern** with ShellExecutor.

---

## Architecture Changes

### Before (Messy)
```
❌ Mixed concerns in one class
❌ Hard-coded logic
❌ No separation between consumption and execution
❌ Difficult to extend
❌ Poor error handling

WorkerStreamConsumer.java (100+ lines)
├─ Stream consumption logic
├─ Task execution logic (mixed)
├─ Callback logic (mixed)
└─ Everything in one file
```

### After (Clean)
```
✅ Clear separation of concerns
✅ Extensible executor pattern
✅ Easy to add new executors
✅ Proper error handling
✅ Well-documented code

worker-service/
├─ model/
│   ├─ TaskExecutionContext.java    (Task input data)
│   └─ TaskExecutionResult.java     (Task output data)
├─ executor/
│   ├─ TaskExecutorInterface.java   (Interface)
│   ├─ ExecutorFactory.java         (Factory pattern)
│   └─ impl/
│       └─ ShellTaskExecutor.java   (Shell implementation)
├─ service/
│   ├─ TaskStreamConsumer.java      (Redis Stream consumption)
│   └─ OrchestratorCallbackService.java (Orchestrator communication)
└─ config/
    └─ RestTemplateConfig.java      (HTTP client config)
```

---

## Component Breakdown

### 1. TaskExecutionContext (Model)

**Purpose:** Contains all information needed to execute a task

**Location:** `worker-service/src/main/java/com/worker/model/TaskExecutionContext.java`

**Key Fields:**
```java
- workflowRunId: String          // Workflow run identifier
- taskId: String                 // Task identifier
- taskType: String               // Executor type (SHELL, HTTP, PYTHON, DOCKER)
- config: Map<String, Object>    // Executor-specific configuration
- timeout: Long                  // Task timeout in milliseconds
- workingDirectory: String       // Working directory for execution
- environment: Map<String, String> // Environment variables
```

**Benefits:**
- ✅ Type-safe data transfer
- ✅ Immutable with Lombok @Builder
- ✅ Easy to extend with new fields
- ✅ Clear API for executors

---

### 2. TaskExecutionResult (Model)

**Purpose:** Contains the result of task execution

**Location:** `worker-service/src/main/java/com/worker/model/TaskExecutionResult.java`

**Key Fields:**
```java
- success: boolean           // Task succeeded or failed
- output: String            // Standard output
- errorOutput: String       // Standard error
- exitCode: Integer         // Process exit code
- errorMessage: String      // Error description
- executionTimeMs: Long     // Execution duration
```

**Static Factory Methods:**
```java
TaskExecutionResult.success(String output)
TaskExecutionResult.failure(String errorMessage)
TaskExecutionResult.failure(int exitCode, String errorOutput)
```

**Benefits:**
- ✅ Clear success/failure indication
- ✅ Rich error information
- ✅ Performance metrics included
- ✅ Easy to construct with factory methods

---

### 3. TaskExecutor Interface

**Purpose:** Contract that all executors must implement

**Location:** `worker-service/src/main/java/com/worker/executor/TaskExecutorInterface.java`

**Interface:**
```java
public interface TaskExecutor {
    TaskExecutionResult execute(TaskExecutionContext context);
    boolean supports(String taskType);
}
```

**Why this design:**
- ✅ Strategy Pattern - different execution strategies
- ✅ Open/Closed Principle - open for extension, closed for modification
- ✅ Easy to add new executors (HTTP, Python, Docker) without changing existing code

---

### 4. ShellTaskExecutor (Implementation)

**Purpose:** Executes shell commands using bash

**Location:** `worker-service/src/main/java/com/worker/executor/impl/ShellTaskExecutor.java`

**Features:**
- ✅ Executes bash commands via ProcessBuilder
- ✅ Captures stdout and stderr separately
- ✅ Supports timeout (kills process if exceeded)
- ✅ Supports custom working directory
- ✅ Supports environment variables
- ✅ Proper error handling and logging
- ✅ Measures execution time

**Example Usage:**
```java
TaskExecutionContext context = TaskExecutionContext.builder()
    .taskId("task1")
    .taskType("SHELL")
    .config(Map.of("command", "echo 'Hello World'"))
    .timeout(30000L)  // 30 seconds
    .build();

TaskExecutionResult result = shellExecutor.execute(context);
// result.isSuccess() -> true
// result.getOutput() -> "Hello World"
```

**Backward Compatibility:**
- Supports taskType="SHELL"
- Also supports null or empty taskType (defaults to shell)

---

### 5. ExecutorFactory

**Purpose:** Factory for creating the right executor based on task type

**Location:** `worker-service/src/main/java/com/worker/executor/ExecutorFactory.java`

**How it works:**
```java
@Component
public class ExecutorFactory {
    private final List<TaskExecutor> executors;  // Auto-injected by Spring
    
    public TaskExecutor getExecutor(String taskType) {
        return executors.stream()
            .filter(e -> e.supports(taskType))
            .findFirst()
            .orElseThrow(() -> new UnsupportedTaskTypeException(...));
    }
}
```

**Benefits:**
- ✅ Automatic executor discovery via Spring dependency injection
- ✅ No code changes needed to add new executors (just create @Component)
- ✅ Clear error message if unsupported task type
- ✅ Factory Pattern - encapsulates executor creation logic

**Adding new executors (future):**
```java
// Just create a new @Component implementing TaskExecutor
@Component
public class HttpTaskExecutor implements TaskExecutor {
    public boolean supports(String taskType) {
        return "HTTP".equals(taskType);
    }
    // ... implementation
}
// Factory automatically picks it up!
```

---

### 6. TaskStreamConsumer

**Purpose:** Consumes tasks from Redis Stream and orchestrates execution

**Location:** `worker-service/src/main/java/com/worker/service/TaskStreamConsumer.java`

**Responsibilities:**
1. ✅ Connect to Redis Stream
2. ✅ Create consumer group (if not exists)
3. ✅ Poll for messages
4. ✅ Parse task data
5. ✅ Build execution context
6. ✅ Get appropriate executor from factory
7. ✅ Execute task
8. ✅ Report result to orchestrator
9. ✅ Acknowledge message
10. ✅ Handle errors gracefully

**Flow:**
```
Start Consumer
    ↓
Ensure consumer group exists
    ↓
Loop forever:
    ├─ Read messages from stream (blocking, 2s timeout)
    ├─ For each message:
    │   ├─ Parse task data
    │   ├─ Build TaskExecutionContext
    │   ├─ Get executor from factory
    │   ├─ Execute task
    │   ├─ Report to orchestrator
    │   └─ Acknowledge message
    └─ Handle errors (sleep 1s on error)
```

**Configuration (application.properties):**
```properties
worker.stream.key=tasks_stream              # Stream name
worker.consumer.group=worker-group          # Consumer group
worker.consumer.name=                       # Auto-generated
worker.poll.timeout-seconds=2               # Blocking read timeout
worker.error.sleep-ms=1000                  # Sleep on error
```

**Startup:**
- Starts automatically via `@PostConstruct`
- Runs in daemon thread
- Logs consumer details on startup

---

### 7. OrchestratorCallbackService

**Purpose:** Reports task completion back to orchestrator

**Location:** `worker-service/src/main/java/com/worker/service/OrchestratorCallbackService.java`

**Features:**
- ✅ HTTP POST to orchestrator callback URL
- ✅ Retry logic (default: 3 retries)
- ✅ Exponential backoff (configurable delay)
- ✅ Detailed logging (success/failure)
- ✅ Graceful error handling (doesn't crash worker if callback fails)

**Configuration:**
```properties
orchestrator.callback.url=http://localhost:8080/api/workflows/task/callback
orchestrator.callback.retries=3
orchestrator.callback.retry-delay-ms=1000
```

**Flow:**
```
Report Task Completion
    ↓
Attempt 1: POST to orchestrator
    ├─ Success? → Log and return
    └─ Failed? → Sleep 1s, retry
         ↓
Attempt 2: POST to orchestrator
    ├─ Success? → Log and return
    └─ Failed? → Sleep 1s, retry
         ↓
Attempt 3: POST to orchestrator
    ├─ Success? → Log and return
    └─ Failed? → Log final failure (task still marked as completed locally)
```

**Payload sent to orchestrator:**
```json
{
  "workflowRunId": "abc-123",
  "taskId": "task1",
  "success": true,
  "lastError": null
}
```

---

### 8. RestTemplateConfig

**Purpose:** Configure HTTP client for orchestrator callbacks

**Location:** `worker-service/src/main/java/com/worker/config/RestTemplateConfig.java`

**Configuration:**
- Connect timeout: 5 seconds
- Read timeout: 30 seconds
- Uses Spring's RestTemplateBuilder for best practices

---

## Data Flow

### Complete Task Execution Flow

```
1. Orchestrator publishes task to Redis Stream
      ↓
2. TaskStreamConsumer reads message from stream
      ↓
3. Parse message data:
   - workflowRunId: "wf-123"
   - taskId: "task1"
   - taskType: "SHELL"
   - config: { "command": "echo hello" }
      ↓
4. Build TaskExecutionContext
      ↓
5. ExecutorFactory.getExecutor("SHELL")
   → Returns ShellTaskExecutor
      ↓
6. ShellTaskExecutor.execute(context)
   ├─ ProcessBuilder("bash", "-c", "echo hello")
   ├─ Wait for completion
   ├─ Capture output: "hello"
   └─ Return TaskExecutionResult(success=true, output="hello")
      ↓
7. WorkerHealthIndicator.recordTaskProcessed()
      ↓
8. OrchestratorCallbackService.reportTaskCompletion(...)
   ├─ POST http://localhost:8080/api/workflows/task/callback
   ├─ Payload: {"workflowRunId":"wf-123", "taskId":"task1", "success":true}
   └─ Retry on failure
      ↓
9. Acknowledge message in Redis Stream
      ↓
10. Loop back to step 2 (wait for next task)
```

---

## Message Format

### Redis Stream Message (from Orchestrator)

**Current format (backward compatible):**
```java
{
  "workflowRunId": "wf-abc-123",
  "taskId": "task1",
  "command": "echo 'Hello World'"  // For backward compatibility
}
```

**New format (with executor support):**
```java
{
  "workflowRunId": "wf-abc-123",
  "taskId": "task1",
  "taskType": "SHELL",  // or "HTTP", "PYTHON", "DOCKER"
  "config": {
    "command": "echo 'Hello World'",
    "timeout": 30000
  }
}
```

**Backward Compatibility:**
- If `command` is present at root level → extracted to `config.command`
- If `taskType` is missing → defaults to "SHELL"
- Old workflows continue to work without changes

---

## Configuration Reference

### application.properties

```properties
# --- WORKER CONFIGURATION ---
# Redis Stream configuration
worker.stream.key=tasks_stream                    # Name of Redis Stream
worker.consumer.group=worker-group                # Consumer group name
worker.consumer.name=                             # Auto-generated (worker-{uuid})
worker.poll.timeout-seconds=2                     # How long to wait for messages
worker.error.sleep-ms=1000                        # Sleep after error

# Orchestrator callback configuration
orchestrator.callback.url=http://localhost:8080/api/workflows/task/callback
orchestrator.callback.retries=3                   # Number of retry attempts
orchestrator.callback.retry-delay-ms=1000        # Delay between retries

# Executor configuration
executor.shell.enabled=true                       # Enable shell executor
# executor.http.enabled=true                      # (Future) HTTP executor
# executor.python.enabled=true                    # (Future) Python executor
# executor.docker.enabled=true                    # (Future) Docker executor
```

---

## Advantages of New Architecture

### 1. **Separation of Concerns**
- ✅ Stream consumption → TaskStreamConsumer
- ✅ Task execution → Executors
- ✅ Callback logic → OrchestratorCallbackService
- ✅ Configuration → RestTemplateConfig

### 2. **Extensibility**
- ✅ Add new executor: Just create a class implementing `TaskExecutor`
- ✅ No need to modify existing code
- ✅ Factory automatically discovers new executors

### 3. **Testability**
- ✅ Each component can be unit tested independently
- ✅ Mock executors for testing consumer
- ✅ Mock callback service for testing executors
- ✅ Clear interfaces make mocking easy

### 4. **Maintainability**
- ✅ Small, focused classes (< 200 lines each)
- ✅ Clear responsibilities
- ✅ Well-documented
- ✅ Easy to understand

### 5. **Configurability**
- ✅ All magic numbers moved to properties
- ✅ Environment-specific overrides
- ✅ Easy to tune for production

### 6. **Error Handling**
- ✅ Graceful degradation
- ✅ Retry logic for transient failures
- ✅ Detailed error logging
- ✅ Health metrics integration

### 7. **Performance**
- ✅ Execution time tracking
- ✅ Timeout support prevents hung tasks
- ✅ Efficient stream polling

---

## Comparison: Before vs After

| Aspect | Before (Messy) | After (Clean) |
|--------|---------------|---------------|
| **Lines of code** | 100+ in one file | 50-150 per file, 6 files |
| **Testability** | ❌ Hard to test | ✅ Easy to unit test |
| **Extensibility** | ❌ Modify existing code | ✅ Just add new class |
| **Error handling** | ❌ Basic try-catch | ✅ Comprehensive retry logic |
| **Configuration** | ❌ Hard-coded values | ✅ Externalized properties |
| **Documentation** | ❌ Minimal comments | ✅ Javadoc + architecture docs |
| **Separation** | ❌ Mixed concerns | ✅ Clear single responsibility |
| **Logging** | ❌ println statements | ✅ Structured SLF4J logging |

---

## Next Steps

### To add HTTP Executor (Week 3, Day 2):
1. Create `HttpTaskExecutor.java` in `executor/impl/`
2. Implement `TaskExecutor` interface
3. Add `supports("HTTP")` method
4. Spring auto-discovers it - no other changes needed!

### To add Python Executor (Week 3, Day 3-4):
1. Create `PythonTaskExecutor.java` in `executor/impl/`
2. Implement virtualenv creation
3. Implement requirements installation
4. Implement Python script execution
5. Auto-discovered by factory!

### To add Docker Executor (Week 3, Day 5):
1. Create `DockerTaskExecutor.java` in `executor/impl/`
2. Add Docker Java client dependency
3. Implement container lifecycle
4. Auto-discovered by factory!

---

## Testing

### Example Unit Test for ShellTaskExecutor

```java
@Test
void shellExecutor_shouldExecuteCommandSuccessfully() {
    // Given
    TaskExecutionContext context = TaskExecutionContext.builder()
        .taskId("test-task")
        .taskType("SHELL")
        .config(Map.of("command", "echo 'Hello'"))
        .build();
    
    ShellTaskExecutor executor = new ShellTaskExecutor();
    
    // When
    TaskExecutionResult result = executor.execute(context);
    
    // Then
    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getOutput()).contains("Hello");
    assertThat(result.getExitCode()).isEqualTo(0);
}
```

### Example Integration Test

```java
@SpringBootTest
class TaskStreamConsumerIntegrationTest {
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
    @Test
    void shouldConsumeAndExecuteTask() {
        // Given: Publish task to stream
        Map<String, String> taskData = Map.of(
            "workflowRunId", "wf-123",
            "taskId", "task1",
            "command", "echo 'test'"
        );
        redisTemplate.opsForStream().add("tasks_stream", taskData);
        
        // Wait for processing
        await().atMost(5, SECONDS)
            .untilAsserted(() -> {
                // Verify task was executed and callback was made
                // ... verification logic
            });
    }
}
```

---

## Summary

The worker-service has been transformed from a **messy, monolithic service** into a **clean, modular, extensible architecture** that:

✅ **Implements Executor Pattern** - Easy to add HTTP, Python, Docker executors  
✅ **Separates Concerns** - Each class has one clear responsibility  
✅ **Highly Testable** - Clear interfaces, easy to mock  
✅ **Well Configured** - All settings externalized  
✅ **Production Ready** - Proper error handling, retry logic, health metrics  
✅ **Backward Compatible** - Works with existing workflows  
✅ **Future Proof** - Ready for Week 3 multi-executor implementation  

The code is now **professional, maintainable, and ready for your job market crawler use case**! 🚀

