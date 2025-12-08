# Worker Service Workflow - Code Walkthrough

## 📋 Overview

The Worker Service is a **task execution engine** that:
1. **Listens** to Redis Streams for incoming tasks
2. **Executes** tasks using appropriate executors (Shell, HTTP, Python, Docker)
3. **Reports** results back to the API Orchestrator

---

## 🏗️ Architecture Components

```
┌─────────────────────────────────────────────────────────┐
│          WORKER SERVICE COMPONENTS                       │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌────────────────────────────────────────────────┐    │
│  │   TaskStreamConsumer (Main Loop)               │    │
│  │   - Polls Redis Stream for tasks               │    │
│  │   - Processes messages                         │    │
│  │   - Coordinates execution                      │    │
│  └────────────────────────────────────────────────┘    │
│                     ↓                                    │
│  ┌────────────────────────────────────────────────┐    │
│  │   ExecutorFactory                              │    │
│  │   - Routes tasks to correct executor           │    │
│  └────────────────────────────────────────────────┘    │
│                     ↓                                    │
│  ┌────────────────────────────────────────────────┐    │
│  │   TaskExecutor Implementations                 │    │
│  │   - ShellTaskExecutor (bash commands)          │    │
│  │   - HttpTaskExecutor (TODO)                    │    │
│  │   - PythonTaskExecutor (TODO)                  │    │
│  │   - DockerTaskExecutor (TODO)                  │    │
│  └────────────────────────────────────────────────┘    │
│                     ↓                                    │
│  ┌────────────────────────────────────────────────┐    │
│  │   OrchestratorCallbackService                  │    │
│  │   - Reports completion via HTTP POST           │    │
│  └────────────────────────────────────────────────┘    │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

---

## 🔄 Complete Workflow (Step-by-Step)

### **Phase 1: Application Startup**

#### 1️⃣ Spring Boot Initialization
```java
// WorkerServiceApplication.java
@SpringBootApplication
public class WorkerServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkerServiceApplication.class, args);
    }
}
```

#### 2️⃣ Bean Creation & Dependency Injection
Spring creates and injects:
- `RedisTemplate<String, String>` - for Redis operations
- `RestTemplate` - for HTTP callbacks to orchestrator
- `ExecutorFactory` - manages all task executors
- `List<TaskExecutor>` - all executor implementations (ShellTaskExecutor, etc.)
- `OrchestratorCallbackService` - handles callbacks
- `TaskStreamConsumer` - main consumer loop

#### 3️⃣ Configuration Loading (application.properties)
```properties
# Redis Stream configuration
worker.stream.key=tasks_stream              # Which Redis Stream to read from
worker.consumer.group=worker-group          # Consumer group name (for load balancing)
worker.consumer.name=                       # Worker name (auto-generated if empty)
worker.poll.timeout-seconds=2               # How long to block waiting for messages
worker.error.sleep-ms=1000                  # Sleep time after errors

# Orchestrator callback
orchestrator.callback.url=http://localhost:8080/api/workflows/task/callback
orchestrator.callback.retries=3
orchestrator.callback.retry-delay-ms=1000
```

These values are injected using `@Value` annotations:
```java
@Value("${worker.stream.key:tasks_stream}")
private String streamKey;  // "tasks_stream" is default if not in .properties
```

---

### **Phase 2: Consumer Startup**

#### 4️⃣ @PostConstruct - Auto-Start Consumer Thread
```java
// TaskStreamConsumer.java
@PostConstruct
public void startConsumer() {
    // Generate unique consumer name if not provided
    if (consumerName == null || consumerName.trim().isEmpty()) {
        consumerName = "worker-" + UUID.randomUUID().toString().substring(0, 8);
        // Example: "worker-a3f5b2c1"
    }

    // Create daemon thread for continuous polling
    Thread consumerThread = new Thread(this::consumeTasksLoop, "TaskStreamConsumer");
    consumerThread.setDaemon(true);  // Dies when main app dies
    consumerThread.start();

    log.info("🚀 Task stream consumer started: group={}, consumer={}, stream={}",
            consumerGroup, consumerName, streamKey);
}
```

**Key Points:**
- Runs automatically after bean construction
- Creates a background daemon thread
- Each worker instance gets a unique consumer name
- Multiple workers can run in same consumer group (load balancing)

---

### **Phase 3: Main Consumer Loop**

#### 5️⃣ Create Consumer Group (First Time)
```java
private void ensureConsumerGroupExists() {
    try {
        redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.latest(), consumerGroup);
        log.info("✅ Created Redis Stream consumer group: {}", consumerGroup);
    } catch (Exception e) {
        // Group already exists - this is fine
        log.debug("Consumer group '{}' already exists", consumerGroup);
    }
}
```

**Redis Stream Consumer Groups:**
- Allows multiple workers to share workload
- Each message goes to ONE consumer in the group
- Supports message acknowledgment
- Handles failures and redelivery

#### 6️⃣ Infinite Loop - Poll for Tasks
```java
private void consumeTasksLoop() {
    ensureConsumerGroupExists();

    while (!Thread.currentThread().isInterrupted()) {
        try {
            // BLOCKING READ - waits up to 2 seconds for new messages
            List<MapRecord<String, Object, Object>> messages = readMessagesFromStream();

            if (messages == null || messages.isEmpty()) {
                continue;  // No messages, loop again
            }

            // Process each message sequentially
            for (MapRecord<String, Object, Object> message : messages) {
                processTaskMessage(message);
                acknowledgeMessage(message);
            }

        } catch (Exception e) {
            log.error("❌ Error in consumer loop: {}", e.getMessage(), e);
            sleepOnError();  // Sleep 1 second before retrying
        }
    }
}
```

#### 7️⃣ Read Messages from Redis Stream
```java
private List<MapRecord<String, Object, Object>> readMessagesFromStream() {
    return redisTemplate.opsForStream().read(
        Consumer.from(consumerGroup, consumerName),  // "worker-group", "worker-a3f5b2c1"
        StreamReadOptions.empty().block(Duration.ofSeconds(pollTimeoutSeconds)),  // Block 2s
        StreamOffset.create(streamKey, ReadOffset.lastConsumed())  // Read new messages only
    );
}
```

**What this does:**
- Identifies this worker to Redis (consumer group + consumer name)
- Blocks for up to 2 seconds waiting for new messages
- Only reads messages not yet consumed by this consumer group
- Returns list of messages (or empty if timeout)

---

### **Phase 4: Task Processing**

#### 8️⃣ Extract Task Data from Message
```java
private void processTaskMessage(MapRecord<String, Object, Object> record) {
    Map<Object, Object> messageData = record.getValue();
    
    // Extract required fields
    String workflowRunId = (String) messageData.get("workflowRunId");  // "run_abc123"
    String taskId = (String) messageData.get("taskId");                 // "task-1"
    String taskType = (String) messageData.getOrDefault("taskType", "SHELL");  // "SHELL"

    log.info("👷 Worker received task: workflowRunId={}, taskId={}, type={}",
            workflowRunId, taskId, taskType);

    // Build execution context
    TaskExecutionContext context = buildExecutionContext(messageData);
    
    // Execute task
    TaskExecutionResult result = executeTask(context);
    
    // Update health metrics
    WorkerHealthIndicator.recordTaskProcessed();
    
    // Report back to orchestrator
    reportTaskCompletion(workflowRunId, taskId, result);
}
```

**Example Message Structure:**
```json
{
  "workflowRunId": "run_abc123",
  "taskId": "task-1",
  "taskType": "SHELL",
  "command": "echo 'Hello World'",
  "timeout": "30000",
  "config": {
    "command": "python script.py",
    "workingDir": "/tmp"
  }
}
```

#### 9️⃣ Build Task Execution Context
```java
private TaskExecutionContext buildExecutionContext(Map<Object, Object> messageData) {
    String workflowRunId = (String) messageData.get("workflowRunId");
    String taskId = (String) messageData.get("taskId");
    String taskType = (String) messageData.getOrDefault("taskType", "SHELL");

    // Extract config
    Map<String, Object> config = new HashMap<>();
    
    // Backward compatibility: if "command" at root level
    if (messageData.containsKey("command")) {
        config.put("command", messageData.get("command"));
    }
    
    // Merge config map if provided
    if (messageData.containsKey("config") && messageData.get("config") instanceof Map) {
        Map<String, Object> configMap = (Map<String, Object>) messageData.get("config");
        config.putAll(configMap);
    }

    // Extract timeout
    Long timeout = null;
    if (messageData.containsKey("timeout")) {
        timeout = Long.parseLong(messageData.get("timeout").toString());
    }

    return TaskExecutionContext.builder()
            .workflowRunId(workflowRunId)
            .taskId(taskId)
            .taskType(taskType)
            .config(config)
            .timeout(timeout)
            .build();
}
```

**TaskExecutionContext Model:**
```java
@Data @Builder
public class TaskExecutionContext {
    private String workflowRunId;           // "run_abc123"
    private String taskId;                  // "task-1"
    private String taskType;                // "SHELL"
    private Map<String, Object> config;     // {"command": "ls -la"}
    private Long timeout;                   // 30000 (ms)
    private String workingDirectory;        // "/tmp"
    private Map<String, String> environment; // {"ENV_VAR": "value"}
}
```

---

### **Phase 5: Task Execution**

#### 🔟 Select Executor via Factory
```java
private TaskExecutionResult executeTask(TaskExecutionContext context) {
    try {
        // Get the appropriate executor based on taskType
        TaskExecutor executor = executorFactory.getExecutor(context.getTaskType());
        
        log.info("Executing task {} with {} executor",
                context.getTaskId(), context.getTaskType());
        
        // Execute the task
        TaskExecutionResult result = executor.execute(context);
        
        if (result.isSuccess()) {
            log.info("✅ Task {} completed successfully in {}ms",
                    context.getTaskId(), result.getExecutionTimeMs());
        } else {
            log.error("❌ Task {} failed: {}",
                     context.getTaskId(), result.getErrorMessage());
        }
        
        return result;
        
    } catch (ExecutorFactory.UnsupportedTaskTypeException e) {
        log.error("❌ Unsupported task type '{}' for task {}",
                 context.getTaskType(), context.getTaskId());
        return TaskExecutionResult.failure("Unsupported task type: " + context.getTaskType());
    }
}
```

#### 1️⃣1️⃣ ExecutorFactory - Route to Correct Executor
```java
// ExecutorFactory.java
@Component
public class ExecutorFactory {
    private final List<TaskExecutor> executors;  // All executor beans
    
    public TaskExecutor getExecutor(String taskType) {
        return executors.stream()
                .filter(executor -> executor.supports(taskType))  // Ask each executor
                .findFirst()
                .orElseThrow(() -> new UnsupportedTaskTypeException(
                        "No executor found for task type: " + taskType
                ));
    }
}
```

**How it works:**
1. Spring auto-injects all `@Component` beans implementing `TaskExecutor`
2. Currently: `ShellTaskExecutor` (future: HttpTaskExecutor, PythonTaskExecutor, DockerTaskExecutor)
3. Calls `supports(taskType)` on each executor
4. Returns first match

#### 1️⃣2️⃣ ShellTaskExecutor - Execute Bash Command
```java
@Component
public class ShellTaskExecutor implements TaskExecutor {
    
    @Override
    public boolean supports(String taskType) {
        return "SHELL".equalsIgnoreCase(taskType) || taskType == null || taskType.trim().isEmpty();
    }
    
    @Override
    public TaskExecutionResult execute(TaskExecutionContext context) {
        long startTime = System.currentTimeMillis();
        
        // 1. Extract command
        String command = context.getConfigString("command");
        if (command == null || command.trim().isEmpty()) {
            return TaskExecutionResult.failure("Shell command is required");
        }
        
        log.info("Executing shell command for task {}: {}", context.getTaskId(), command);
        
        // 2. Build process
        ProcessBuilder processBuilder = new ProcessBuilder("bash", "-c", command);
        
        // 3. Set working directory
        if (context.getWorkingDirectory() != null) {
            processBuilder.directory(new File(context.getWorkingDirectory()));
        }
        
        // 4. Set environment variables
        if (context.getEnvironment() != null && !context.getEnvironment().isEmpty()) {
            processBuilder.environment().putAll(context.getEnvironment());
        }
        
        processBuilder.redirectErrorStream(false);  // Separate stdout and stderr
        
        // 5. Start process
        Process process = processBuilder.start();
        
        // 6. Capture output streams
        BufferedReader outputReader = new BufferedReader(
            new InputStreamReader(process.getInputStream())
        );
        BufferedReader errorReader = new BufferedReader(
            new InputStreamReader(process.getErrorStream())
        );
        
        String output = outputReader.lines().collect(Collectors.joining("\n"));
        String errorOutput = errorReader.lines().collect(Collectors.joining("\n"));
        
        // 7. Wait for completion with timeout
        boolean completed;
        if (context.getTimeout() != null && context.getTimeout() > 0) {
            completed = process.waitFor(context.getTimeout(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();  // Kill process
                long duration = System.currentTimeMillis() - startTime;
                return TaskExecutionResult.builder()
                        .success(false)
                        .errorMessage("Task execution timed out after " + context.getTimeout() + "ms")
                        .executionTimeMs(duration)
                        .build();
            }
        } else {
            process.waitFor();  // Wait indefinitely
        }
        
        // 8. Get exit code
        int exitCode = process.exitValue();
        long duration = System.currentTimeMillis() - startTime;
        
        // 9. Build result
        TaskExecutionResult result = TaskExecutionResult.builder()
                .success(exitCode == 0)
                .output(output)
                .errorOutput(errorOutput)
                .exitCode(exitCode)
                .executionTimeMs(duration)
                .build();
        
        if (!result.isSuccess()) {
            result.setErrorMessage("Process exited with code " + exitCode);
        }
        
        return result;
    }
}
```

**TaskExecutionResult Model:**
```java
@Data @Builder
public class TaskExecutionResult {
    private boolean success;           // true if exitCode == 0
    private String output;             // stdout
    private String errorOutput;        // stderr
    private Integer exitCode;          // 0, 1, 127, etc.
    private String errorMessage;       // Human-readable error
    private Long executionTimeMs;      // Duration
}
```

---

### **Phase 6: Report Results**

#### 1️⃣3️⃣ Callback to Orchestrator
```java
private void reportTaskCompletion(String workflowRunId, String taskId, TaskExecutionResult result) {
    callbackService.reportTaskCompletion(
            workflowRunId,
            taskId,
            result.isSuccess(),
            result.getErrorMessage()
    );
}
```

#### 1️⃣4️⃣ OrchestratorCallbackService - HTTP POST with Retry
```java
@Service
public class OrchestratorCallbackService {
    private final RestTemplate restTemplate;
    
    @Value("${orchestrator.callback.url:http://localhost:8080/api/workflows/task/callback}")
    private String callbackUrl;
    
    @Value("${orchestrator.callback.retries:3}")
    private int maxRetries;
    
    @Value("${orchestrator.callback.retry-delay-ms:1000}")
    private long retryDelayMs;
    
    public void reportTaskCompletion(String workflowRunId, String taskId, 
                                      boolean success, String errorMessage) {
        // Build JSON payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("workflowRunId", workflowRunId);
        payload.put("taskId", taskId);
        payload.put("success", success);
        payload.put("lastError", errorMessage);
        
        reportWithRetry(payload);
    }
    
    private void reportWithRetry(Map<String, Object> payload) {
        int attempt = 0;
        
        while (attempt < maxRetries) {
            attempt++;
            try {
                ResponseEntity<Void> response = restTemplate.postForEntity(
                        callbackUrl,  // POST http://localhost:8080/api/workflows/task/callback
                        payload,      // JSON body
                        Void.class
                );
                
                if (response.getStatusCode().is2xxSuccessful()) {
                    log.info("✅ Successfully reported task {} completion", payload.get("taskId"));
                    return;  // Success!
                }
                
            } catch (RestClientException e) {
                log.error("❌ Failed to report to orchestrator (attempt {}/{}): {}",
                         attempt, maxRetries, e.getMessage());
                
                if (attempt < maxRetries) {
                    Thread.sleep(retryDelayMs);  // Wait 1 second before retry
                }
            }
        }
        
        log.error("🚫 Failed to report task {} completion after {} attempts",
                 payload.get("taskId"), maxRetries);
    }
}
```

**Callback Payload Example:**
```json
{
  "workflowRunId": "run_abc123",
  "taskId": "task-1",
  "success": true,
  "lastError": null
}
```

#### 1️⃣5️⃣ Acknowledge Message in Redis
```java
private void acknowledgeMessage(MapRecord<String, Object, Object> message) {
    try {
        redisTemplate.opsForStream().acknowledge(streamKey, consumerGroup, message.getId());
        log.debug("Acknowledged message: {}", message.getId());
    } catch (Exception e) {
        log.error("Failed to acknowledge message {}: {}", message.getId(), e.getMessage());
    }
}
```

**Why Acknowledge?**
- Tells Redis "I successfully processed this message"
- Prevents redelivery
- If worker crashes before ACK, another worker can claim the message

---

## 🔁 Complete Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                    WORKER SERVICE LIFECYCLE                          │
└─────────────────────────────────────────────────────────────────────┘

1. [APP STARTUP]
   ↓
2. Spring Boot loads application.properties
   ↓
3. Spring creates all beans (RedisTemplate, RestTemplate, Executors, etc.)
   ↓
4. @PostConstruct → TaskStreamConsumer.startConsumer()
   ↓
5. Create background daemon thread
   ↓
6. Create Redis consumer group "worker-group" (if not exists)
   ↓
7. ┌─────────────────────────────────────────────────┐
   │  INFINITE LOOP (while not interrupted)          │
   │                                                  │
   │  8. Read from Redis Stream (BLOCK 2 seconds)    │
   │     ↓                                            │
   │  9. If no messages → loop again                 │
   │     ↓                                            │
   │  10. For each message:                          │
   │      ├─ Extract workflowRunId, taskId, type     │
   │      ├─ Build TaskExecutionContext              │
   │      ├─ Get executor from factory               │
   │      ├─ Execute task (ShellTaskExecutor)        │
   │      │   ├─ Start bash process                  │
   │      │   ├─ Capture stdout/stderr               │
   │      │   ├─ Wait with timeout                   │
   │      │   └─ Return TaskExecutionResult          │
   │      ├─ Report to orchestrator (HTTP POST)      │
   │      │   └─ Retry up to 3 times                 │
   │      └─ Acknowledge message in Redis            │
   │                                                  │
   │  11. Loop back to step 8                        │
   └─────────────────────────────────────────────────┘
```

---

## 🎯 Key Concepts Explained

### **Redis Streams**
- **Stream**: Append-only log of messages (like Kafka topic)
- **Consumer Group**: Multiple workers share workload
- **Consumer**: Individual worker instance
- **Acknowledgment**: Confirms message processed successfully

### **@Value Annotation & Property Injection**
```java
@Value("${worker.stream.key:tasks_stream}")
private String streamKey;
```
- Reads from `application.properties`: `worker.stream.key=tasks_stream`
- If property not found, uses default: `tasks_stream`
- Injected at bean creation time

### **@PostConstruct**
- Runs automatically after bean is fully initialized
- Perfect for starting background threads
- Runs once per bean instance

### **Daemon Threads**
```java
consumerThread.setDaemon(true);
```
- Background thread that doesn't prevent JVM shutdown
- Dies automatically when main application stops
- Good for worker loops

### **ExecutorFactory Pattern**
- **Strategy Pattern**: Select algorithm at runtime
- Each executor implements same interface
- Factory decides which one to use based on task type
- Easy to add new executors (just add `@Component`)

---

## 📊 Current State & Future Expansion

### ✅ **Currently Implemented**
- ShellTaskExecutor (SHELL type)
- Redis Stream consumer with consumer groups
- Automatic task distribution across workers
- Timeout handling
- HTTP callback with retry logic
- Health indicators

### 🚧 **To Be Implemented**
- **HttpTaskExecutor** - Make HTTP requests (GET, POST, etc.)
- **PythonTaskExecutor** - Run Python scripts
- **DockerTaskExecutor** - Run Docker containers

**Adding a new executor:**
```java
@Component
public class HttpTaskExecutor implements TaskExecutor {
    @Override
    public boolean supports(String taskType) {
        return "HTTP".equalsIgnoreCase(taskType);
    }
    
    @Override
    public TaskExecutionResult execute(TaskExecutionContext context) {
        // Make HTTP request
        // Return result
    }
}
```
That's it! ExecutorFactory auto-discovers it.

---

## 🔧 Configuration Reference

### **application.properties Breakdown**

```properties
# Redis Stream to read from
worker.stream.key=tasks_stream

# Consumer group name (for load balancing multiple workers)
worker.consumer.group=worker-group

# Consumer name (auto-generated if empty: worker-a3f5b2c1)
worker.consumer.name=

# How long to block waiting for messages (seconds)
worker.poll.timeout-seconds=2

# Sleep time after error (milliseconds)
worker.error.sleep-ms=1000

# Orchestrator callback URL
orchestrator.callback.url=http://localhost:8080/api/workflows/task/callback

# Number of retry attempts for callback
orchestrator.callback.retries=3

# Delay between retries (milliseconds)
orchestrator.callback.retry-delay-ms=1000
```

---

## 🎓 Summary

The Worker Service follows this simple workflow:

1. **Start** → Background thread polls Redis Stream
2. **Receive** → Get task message from stream
3. **Parse** → Extract task details and build context
4. **Execute** → Run command via appropriate executor
5. **Report** → POST results back to orchestrator
6. **Acknowledge** → Mark message as processed in Redis
7. **Repeat** → Loop forever

**Key Design Principles:**
- ✅ **Scalable**: Multiple workers share workload via consumer groups
- ✅ **Extensible**: Easy to add new executor types
- ✅ **Resilient**: Retry logic, timeout handling, error recovery
- ✅ **Observable**: Logging, health indicators, metrics
- ✅ **Decoupled**: Redis Stream for async communication


