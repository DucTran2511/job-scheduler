# Multi-Executor Implementation Guide

## Overview

Your job scheduler currently only supports **shell commands**. You need to add support for:
1. **HTTP Executor** - REST API calls (for OpenAI, webhooks, etc.)
2. **Python Executor** - Python script execution (for your crawlers)
3. **Docker Executor** - Containerized task execution (for isolated environments)

---

## Architecture Design

### Current Architecture (Shell Only)

```
Task Definition (YAML)
    ↓
    command: "echo hello"
    ↓
WorkerStreamConsumer
    ↓
ProcessBuilder("bash", "-c", command)
```

### New Architecture (Multi-Executor)

```
Task Definition (YAML)
    ↓
    type: "PYTHON" | "HTTP" | "DOCKER" | "SHELL"
    config: { ... executor-specific config ... }
    ↓
WorkerStreamConsumer
    ↓
ExecutorFactory.create(task.type)
    ↓
    ├─ ShellTaskExecutor
    ├─ HttpTaskExecutor
    ├─ PythonTaskExecutor
    └─ DockerTaskExecutor
```

---

## Implementation Plan

### Phase 1: Refactor Current Shell Executor (1 day)

**Create executor interface:**
```java
public interface TaskExecutor {
    TaskExecutionResult execute(TaskExecutionContext context);
    boolean supports(String taskType);
}
```

**Extract shell execution:**
```java
@Component
public class ShellTaskExecutor implements TaskExecutor {
    @Override
    public TaskExecutionResult execute(TaskExecutionContext context) {
        String command = context.getConfig().get("command");
        Process process = new ProcessBuilder("bash", "-c", command).start();
        // ... existing logic ...
    }
    
    @Override
    public boolean supports(String taskType) {
        return "SHELL".equals(taskType) || taskType == null;
    }
}
```

**Create executor factory:**
```java
@Component
public class ExecutorFactory {
    private final List<TaskExecutor> executors;
    
    public TaskExecutor getExecutor(String taskType) {
        return executors.stream()
            .filter(e -> e.supports(taskType))
            .findFirst()
            .orElseThrow(() -> new UnsupportedExecutorException(taskType));
    }
}
```

---

### Phase 2: HTTP Executor (2 days)

**Purpose:** Call REST APIs (OpenAI, webhooks, third-party services)

**Features:**
- Support all HTTP methods (GET, POST, PUT, DELETE, PATCH)
- Custom headers
- Request body templates
- Response parsing
- Retry on failure
- Timeout configuration

**Implementation:**

```java
@Component
public class HttpTaskExecutor implements TaskExecutor {
    private final RestTemplate restTemplate;
    
    @Override
    public TaskExecutionResult execute(TaskExecutionContext context) {
        HttpTaskConfig config = parseConfig(context);
        
        HttpHeaders headers = buildHeaders(config.getHeaders());
        HttpEntity<String> entity = new HttpEntity<>(config.getBody(), headers);
        
        ResponseEntity<String> response = restTemplate.exchange(
            config.getUrl(),
            HttpMethod.valueOf(config.getMethod()),
            entity,
            String.class
        );
        
        return TaskExecutionResult.builder()
            .success(response.getStatusCode().is2xxSuccessful())
            .output(response.getBody())
            .statusCode(response.getStatusCodeValue())
            .build();
    }
    
    @Override
    public boolean supports(String taskType) {
        return "HTTP".equals(taskType);
    }
}
```

**Task definition example:**
```yaml
- id: "call-openai"
  type: "HTTP"
  config:
    url: "https://api.openai.com/v1/chat/completions"
    method: "POST"
    headers:
      Authorization: "Bearer ${OPENAI_API_KEY}"
      Content-Type: "application/json"
    body: |
      {
        "model": "gpt-4",
        "messages": [{"role": "user", "content": "Extract job data"}]
      }
    timeout: 30000
    retries: 3
```

---

### Phase 3: Python Executor (2 days)

**Purpose:** Execute Python scripts (your crawlers, data processing)

**Features:**
- Virtualenv creation
- Dependency installation (requirements.txt or inline)
- Script execution
- Argument passing
- Environment variables
- Output capture
- Error handling

**Implementation:**

```java
@Component
public class PythonTaskExecutor implements TaskExecutor {
    
    @Override
    public TaskExecutionResult execute(TaskExecutionContext context) {
        PythonTaskConfig config = parseConfig(context);
        
        // 1. Create virtualenv (optional)
        if (config.isUseVirtualenv()) {
            createVirtualenv(context.getWorkDir());
            installRequirements(context.getWorkDir(), config.getRequirements());
        }
        
        // 2. Build command
        List<String> command = new ArrayList<>();
        command.add(getPythonExecutable(context.getWorkDir()));
        command.add(config.getScriptPath());
        command.addAll(config.getArgs());
        
        // 3. Execute
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().putAll(config.getEnv());
        pb.directory(new File(context.getWorkDir()));
        
        Process process = pb.start();
        String output = captureOutput(process.getInputStream());
        String errors = captureOutput(process.getErrorStream());
        
        int exitCode = process.waitFor();
        
        return TaskExecutionResult.builder()
            .success(exitCode == 0)
            .output(output)
            .errors(errors)
            .exitCode(exitCode)
            .build();
    }
    
    @Override
    public boolean supports(String taskType) {
        return "PYTHON".equals(taskType);
    }
    
    private void installRequirements(String workDir, List<String> requirements) {
        if (requirements.isEmpty()) return;
        
        // Create requirements.txt
        Path reqFile = Paths.get(workDir, "requirements.txt");
        Files.write(reqFile, requirements);
        
        // pip install -r requirements.txt
        ProcessBuilder pb = new ProcessBuilder(
            getPythonExecutable(workDir),
            "-m", "pip", "install", "-r", reqFile.toString()
        );
        pb.start().waitFor();
    }
}
```

**Task definition example:**
```yaml
- id: "crawl-linkedin"
  type: "PYTHON"
  config:
    script_path: "/opt/crawlers/linkedin_scraper.py"
    python_version: "3.11"
    use_virtualenv: true
    requirements:
      - beautifulsoup4==4.12.0
      - selenium==4.15.0
      - requests==2.31.0
    args:
      - "--max-pages"
      - "50"
      - "--output"
      - "/tmp/linkedin_jobs.json"
    env:
      LINKEDIN_EMAIL: "${LINKEDIN_EMAIL}"
      PYTHONPATH: "/opt/crawlers"
    timeout: 300000  # 5 minutes
```

---

### Phase 4: Docker Executor (2 days)

**Purpose:** Run tasks in isolated Docker containers

**Features:**
- Image management (pull, use local)
- Volume mounting
- Environment variables
- Resource limits (CPU, memory)
- Network configuration
- Log streaming
- Container cleanup

**Implementation:**

```java
@Component
public class DockerTaskExecutor implements TaskExecutor {
    private final DockerClient dockerClient;
    
    @Override
    public TaskExecutionResult execute(TaskExecutionContext context) {
        DockerTaskConfig config = parseConfig(context);
        
        // 1. Pull image if needed
        if (config.isPullImage()) {
            dockerClient.pullImageCmd(config.getImage()).exec();
        }
        
        // 2. Create container
        CreateContainerResponse container = dockerClient.createContainerCmd(config.getImage())
            .withCmd(config.getCommand())
            .withEnv(buildEnvVars(config.getEnv()))
            .withBinds(buildVolumes(config.getVolumes()))
            .withHostConfig(
                HostConfig.newHostConfig()
                    .withMemory(config.getMemoryLimit())
                    .withCpuQuota(config.getCpuQuota())
            )
            .exec();
        
        // 3. Start container
        dockerClient.startContainerCmd(container.getId()).exec();
        
        // 4. Wait for completion
        WaitContainerResultCallback waitCallback = new WaitContainerResultCallback();
        dockerClient.waitContainerCmd(container.getId()).exec(waitCallback);
        Integer statusCode = waitCallback.awaitStatusCode();
        
        // 5. Get logs
        String logs = getLogs(container.getId());
        
        // 6. Cleanup
        dockerClient.removeContainerCmd(container.getId())
            .withForce(true)
            .exec();
        
        return TaskExecutionResult.builder()
            .success(statusCode == 0)
            .output(logs)
            .exitCode(statusCode)
            .build();
    }
    
    @Override
    public boolean supports(String taskType) {
        return "DOCKER".equals(taskType);
    }
}
```

**Task definition example:**
```yaml
- id: "crawl-with-selenium"
  type: "DOCKER"
  config:
    image: "selenium/standalone-chrome:latest"
    pull_image: true
    command:
      - "python"
      - "/app/crawler.py"
    volumes:
      - "/opt/crawlers:/app:ro"
      - "/tmp/output:/output:rw"
    env:
      HEADLESS: "true"
      MAX_PAGES: "50"
    resources:
      memory: "2GB"
      cpu: "1.0"
    network: "bridge"
    timeout: 600000  # 10 minutes
```

---

## Data Model Changes

### Update TaskDef to Support Executors

```java
@Data
public class TaskDef {
    private String id;
    private String name;
    
    // OLD: Only command
    @Deprecated
    private String command;
    
    // NEW: Executor type and config
    private String type = "SHELL";  // Default to shell for backward compatibility
    private Map<String, Object> config = new HashMap<>();
    
    // Existing fields
    private List<String> dependsOn;
    private Integer maxRetries;
    private Long timeout;
}
```

### Backward Compatibility

```yaml
# Old format (still works - treated as SHELL type)
tasks:
  - id: task1
    command: "echo hello"

# New format
tasks:
  - id: task1
    type: "SHELL"
    config:
      command: "echo hello"
```

---

## Configuration

### application.properties

```properties
# Executor Configuration
executor.shell.enabled=true
executor.http.enabled=true
executor.http.connect-timeout=5000
executor.http.read-timeout=30000
executor.python.enabled=true
executor.python.default-version=3.11
executor.python.virtualenv-path=/tmp/venvs
executor.docker.enabled=true
executor.docker.socket=unix:///var/run/docker.sock
executor.docker.default-network=bridge
```

---

## Testing Strategy

### Unit Tests
```java
@Test
void httpExecutor_shouldCallApiSuccessfully() {
    HttpTaskConfig config = HttpTaskConfig.builder()
        .url("https://api.example.com/data")
        .method("GET")
        .build();
    
    TaskExecutionResult result = httpExecutor.execute(context);
    
    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getStatusCode()).isEqualTo(200);
}
```

### Integration Tests
```java
@SpringBootTest
@Testcontainers
class DockerExecutorIntegrationTest {
    @Container
    static GenericContainer<?> dockerHost = new GenericContainer<>("docker:dind");
    
    @Test
    void dockerExecutor_shouldRunPythonScript() {
        DockerTaskConfig config = DockerTaskConfig.builder()
            .image("python:3.11-slim")
            .command(Arrays.asList("python", "-c", "print('Hello')"))
            .build();
        
        TaskExecutionResult result = dockerExecutor.execute(context);
        
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("Hello");
    }
}
```

---

## Migration Path

### Week 3: Days 1-5

**Day 1:**
- Create executor interfaces
- Refactor current code to ShellTaskExecutor
- Update WorkerStreamConsumer to use ExecutorFactory

**Day 2:**
- Implement HttpTaskExecutor
- Add RestTemplate configuration
- Write unit tests

**Day 3:**
- Implement PythonTaskExecutor (basic)
- Add virtualenv support
- Test with simple Python scripts

**Day 4:**
- Enhance PythonTaskExecutor (requirements, env vars)
- Test with your actual crawler scripts
- Add error handling

**Day 5:**
- Implement DockerTaskExecutor
- Add Docker Java client
- Test with Selenium containers
- Integration testing

---

## Benefits for Your Crawler

### Before (Shell Only):
```yaml
tasks:
  - id: crawl
    command: "python3 /opt/crawlers/linkedin.py > /tmp/output.html"
  - id: extract
    command: "curl -X POST https://api.openai.com/... -d @/tmp/output.html"
  - id: store
    command: "python3 /opt/crawlers/store.py /tmp/output.json"
```

**Problems:**
- ❌ Hacky (using shell to call Python)
- ❌ No proper error handling
- ❌ Can't manage dependencies
- ❌ No isolation
- ❌ Hard to debug

### After (Multi-Executor):
```yaml
tasks:
  - id: crawl
    type: "PYTHON"
    config:
      script_path: "/opt/crawlers/linkedin.py"
      requirements: [beautifulsoup4, selenium]
  
  - id: extract
    type: "HTTP"
    config:
      url: "https://api.openai.com/v1/chat/completions"
      method: "POST"
      headers: {...}
  
  - id: store
    type: "DOCKER"
    config:
      image: "python:3.11"
      command: ["python", "/app/store.py"]
      volumes: ["/data:/app/data"]
```

**Benefits:**
- ✅ Proper Python execution
- ✅ Managed dependencies
- ✅ Built-in error handling
- ✅ Container isolation
- ✅ Easy to maintain

---

## Next Steps

1. Review this guide
2. Start with Day 1 (refactor to ExecutorFactory)
3. Implement executors one by one
4. Test with your actual crawler use case
5. Update documentation

By end of Week 3, you'll have a production-ready multi-executor system that can handle ANY task type!

