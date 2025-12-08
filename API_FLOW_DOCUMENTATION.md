# Technical Flow Documentation - Core APIs

**Document Version:** 1.0  
**Last Updated:** November 20, 2025  
**Audience:** Developers integrating with the Job Scheduler Platform

---

## Table of Contents

1. [API 1: POST /api/workflows/start](#api-1-post-apiworkflowsstart)
2. [API 2: POST /api/workflows/{runId}/tasks/{taskId}/complete](#api-2-post-apiworkflowsrunidtaskstaskidcomplete)
3. [API 3: POST /api/workflows/task/callback](#api-3-post-apiworkflowstaskcallback)
4. [Complete End-to-End Flow](#complete-end-to-end-flow)
5. [Database State Transitions](#database-state-transitions)
6. [Redis Data Structures](#redis-data-structures)

---

## API 1: POST /api/workflows/start

### Overview
This API accepts a YAML/JSON workflow definition, parses it into a DAG (Directed Acyclic Graph), persists the workflow metadata and task definitions to PostgreSQL, caches the DAG structure in Redis, and immediately enqueues all root tasks (tasks with no dependencies) to Redis Streams for worker consumption.

### Request Flow

```
┌─────────────┐
│   Client    │
│  (curl/SDK) │
└──────┬──────┘
       │ POST /api/workflows/start
       │ Content-Type: text/plain
       │ Body: YAML/JSON definition
       ▼
┌──────────────────────────────────────────────────────────┐
│         WorkflowController.startWorkflow()               │
│  - Receives raw YAML/JSON as String                      │
│  - Calls orchestrator.startWorkflow(yamlOrJson)          │
│  - Returns: { "workflowRunId": "uuid-xxx" }              │
└──────┬───────────────────────────────────────────────────┘
       │
       ▼
┌──────────────────────────────────────────────────────────┐
│      WorkflowOrchestrator.startWorkflow()                │
│  ┌────────────────────────────────────────────────────┐  │
│  │ Step 1: Parse YAML/JSON → DagDefinition           │  │
│  │  - DagParser.parseDefinition(yamlOrJson)          │  │
│  │  - Validates YAML syntax                          │  │
│  │  - Converts to DagDefinition object               │  │
│  │    • id, name, description                        │  │
│  │    • List<TaskDef> tasks                          │  │
│  └────────────────────────────────────────────────────┘  │
│                                                           │
│  ┌────────────────────────────────────────────────────┐  │
│  │ Step 2: Build DAG Graph                           │  │
│  │  - DagParser.buildGraph(def)                      │  │
│  │  - Creates DirectedAcyclicGraph<String, Edge>     │  │
│  │  - Validates no cycles exist                      │  │
│  │  - Nodes = task IDs, Edges = dependencies         │  │
│  └────────────────────────────────────────────────────┘  │
│                                                           │
│  ┌────────────────────────────────────────────────────┐  │
│  │ Step 3: Persist WorkflowEntity to PostgreSQL      │  │
│  │  - INSERT INTO workflow_entity                    │  │
│  │    • name, description, raw_definition            │  │
│  │  - Returns workflow.id (UUID)                     │  │
│  └────────────────────────────────────────────────────┘  │
│                                                           │
│  ┌────────────────────────────────────────────────────┐  │
│  │ Step 4: Create WorkflowRun to PostgreSQL          │  │
│  │  - INSERT INTO workflow_run                       │  │
│  │    • workflow_id (FK)                             │  │
│  │    • status = 'RUNNING'                           │  │
│  │    • started_at = NOW()                           │  │
│  │  - Returns run.id (UUID) ← THIS IS RETURNED       │  │
│  └────────────────────────────────────────────────────┘  │
│                                                           │
│  ┌────────────────────────────────────────────────────┐  │
│  │ Step 5: Create TaskRun records (one per task)     │  │
│  │  - For each TaskDef in definition:                │  │
│  │    INSERT INTO task_run                           │  │
│  │      • workflow_run_id (FK)                       │  │
│  │      • task_id (from YAML)                        │  │
│  │      • task_name, command                         │  │
│  │      • status = 'PENDING'                         │  │
│  │      • retry_count = 0                            │  │
│  │      • max_retries (from YAML, default 3)         │  │
│  └────────────────────────────────────────────────────┘  │
│                                                           │
│  ┌────────────────────────────────────────────────────┐  │
│  │ Step 6: Cache DAG in Redis                        │  │
│  │  - redisDagCache.saveDag(runId, definition)       │  │
│  │  - HMSET dag:{runId}:graph                        │  │
│  │    • task1 → ""  (no deps)                        │  │
│  │    • task2 → "task1"  (depends on task1)          │  │
│  │    • task3 → "task1,task2"  (multiple deps)       │  │
│  │  - HMSET dag:{runId}:metadata                     │  │
│  │    • status → "RUNNING"                           │  │
│  │    • createdAt → "2025-11-20T10:30:00"            │  │
│  └────────────────────────────────────────────────────┘  │
│                                                           │
│  ┌────────────────────────────────────────────────────┐  │
│  │ Step 7: Find Root Tasks (no dependencies)         │  │
│  │  - Iterate DAG vertices                           │  │
│  │  - Find tasks where incomingEdges().isEmpty()     │  │
│  │  - Example: [task1, task4] (parallel roots)       │  │
│  └────────────────────────────────────────────────────┘  │
│                                                           │
│  ┌────────────────────────────────────────────────────┐  │
│  │ Step 8: Enqueue Root Tasks to Redis Streams       │  │
│  │  - For each root task:                            │  │
│  │    redisPublisher.publishTask(runId, taskId, cmd) │  │
│  │                                                    │  │
│  │    XADD tasks_stream * \                          │  │
│  │      workflowRunId {runId} \                      │  │
│  │      taskId {taskId} \                            │  │
│  │      command "python crawl.py"                    │  │
│  │                                                    │  │
│  │  - Workers listening on tasks_stream will pick up │  │
│  └────────────────────────────────────────────────────┘  │
│                                                           │
│  ┌────────────────────────────────────────────────────┐  │
│  │ Step 9: Return WorkflowRun ID to Client           │  │
│  │  - return run.getId()                             │  │
│  └────────────────────────────────────────────────────┘  │
└──────┬────────────────────────────────────────────────────┘
       │
       ▼
┌──────────────────────────────────────────────────────────┐
│  Response: HTTP 200 OK                                   │
│  {                                                        │
│    "workflowRunId": "550e8400-e29b-41d4-a716-446655440000"│
│  }                                                        │
└──────────────────────────────────────────────────────────┘
```

### Database State After API Call

**workflow_entity table:**
```sql
| id (PK)  | name                  | description           | raw_definition   |
|----------|----------------------|----------------------|------------------|
| wf-001   | Job Market Crawler   | Crawl job listings...| id: job-market...
```

**workflow_run table:**
```sql
| id (PK)         | workflow_id | status  | started_at           | finished_at |
|-----------------|-------------|---------|---------------------|-------------|
| 550e8400-e29... | wf-001      | RUNNING | 2025-11-20 10:30:00 | NULL        |
```

**task_run table:**
```sql
| id      | workflow_run_id | task_id        | status  | retry_count | command           |
|---------|-----------------|----------------|---------|-------------|-------------------|
| tr-001  | 550e8400-e29... | crawl-linkedin | PENDING | 0           | python crawl.py   |
| tr-002  | 550e8400-e29... | crawl-topcv    | PENDING | 0           | python topcv.py   |
| tr-003  | 550e8400-e29... | ai-extract     | PENDING | 0           | python ai.py      |
```

### Redis State After API Call

**DAG Structure (Hash):**
```
Key: dag:550e8400-e29b-41d4-a716-446655440000:graph
Fields:
  crawl-linkedin → ""
  crawl-topcv    → "crawl-linkedin"
  ai-extract     → "crawl-linkedin,crawl-topcv"
```

**Stream (Tasks Queue):**
```
Key: tasks_stream
Entry 1:
  1700500800000-0  {
    workflowRunId: "550e8400-e29b-41d4-a716-446655440000",
    taskId: "crawl-linkedin",
    command: "python /opt/crawlers/linkedin.py"
  }
```

### Error Scenarios

**Invalid YAML:**
```json
// HTTP 400 Bad Request
{
  "code": "parse_error",
  "message": "Invalid YAML: mapping values are not allowed here in 'string', line 5, column 12"
}
```

**Cyclic Dependency:**
```json
// HTTP 400 Bad Request
{
  "code": "parse_error",
  "message": "Cycle detected in task dependencies: task1 → task2 → task3 → task1"
}
```

---

## API 2: POST /api/workflows/{runId}/tasks/{taskId}/complete

### Overview
This is the **callback endpoint** that workers use to report task completion. It updates the task status in PostgreSQL,
checks if dependent tasks are ready to run (all their dependencies succeeded), enqueues ready tasks to Redis Streams,
and marks the workflow as COMPLETED if all tasks finished successfully.

### Request Flow

```
┌─────────────┐
│   Worker    │
│  (Python/   │
│   Java/etc) │
└──────┬──────┘
       │ POST /api/workflows/{runId}/tasks/{taskId}/complete?success=true
       │ Body: (optional error message)
       ▼
┌──────────────────────────────────────────────────────────┐
│    WorkflowController.taskCompleted()                    │
│  - Extract path params: runId, taskId                    │
│  - Extract query param: success (default true)           │
│  - Extract optional body: errorPayload                   │
│  - Calls orchestrator.onTaskCompleted(...)               │
└──────┬───────────────────────────────────────────────────┘
       │
       ▼
┌──────────────────────────────────────────────────────────┐
│   WorkflowOrchestrator.onTaskCompleted()                 │
│   (runId, taskId, success, lastError)                    │
│                                                           │
│  ┌────────────────────────────────────────────────────┐  │
│  │ Step 1: Find TaskRun Record                        │  │
│  │  - SELECT * FROM task_run                          │  │
│  │    WHERE workflow_run_id = {runId}                 │  │
│  │      AND task_id = {taskId}                        │  │
│  │  - If not found → log warning & return             │  │
│  └────────────────────────────────────────────────────┘  │
│                                                           │
│  ┌──────────── SUCCESS BRANCH ─────────────────────┐     │
│  │ IF success == true:                             │     │
│  │                                                  │     │
│  │ Step 2a: Update TaskRun Status                  │     │
│  │  - UPDATE task_run SET                          │     │
│  │      status = 'SUCCESS',                        │     │
│  │      finished_at = NOW()                        │     │
│  │    WHERE id = {taskRunId}                       │     │
│  │                                                 │     │
│  │ Step 3a: Load DAG from Redis                    │     │
│  │  - HGETALL dag:{runId}:graph                    │     │
│  │  - Returns Map<taskId, dependencies>            │     │
│  │    Example:                                      │     │
│  │    {                                             │     │
│  │      "crawl-linkedin": [],                      │     │
│  │      "crawl-topcv": ["crawl-linkedin"],         │     │
│  │      "ai-extract": ["crawl-linkedin",           │     │
│  │                     "crawl-topcv"]              │     │
│  │    }                                             │     │
│  │                                                  │     │
│  │ Step 4a: Find Dependent Tasks                   │     │
│  │  - Iterate DAG entries                          │     │
│  │  - For each task that depends on {taskId}:      │     │
│  │                                                  │     │
│  │    Example: If crawl-linkedin completed         │     │
│  │             → Check crawl-topcv (depends on it) │     │
│  │             → Check ai-extract (depends on it)  │     │
│  │                                                  │     │
│  │ Step 5a: Check if ALL Dependencies Satisfied    │     │
│  │  - For dependent task "crawl-topcv":            │     │
│  │    Dependencies: ["crawl-linkedin"]             │     │
│  │    Check each dependency:                       │     │
│  │      SELECT status FROM task_run                │     │
│  │      WHERE workflow_run_id = {runId}            │     │
│  │        AND task_id = 'crawl-linkedin'           │     │
│  │                                                  │     │
│  │    IF all dependencies status == 'SUCCESS'      │     │
│  │      → Task is READY to run                     │     │
│  │                                                  │     │
│  │ Step 6a: Enqueue Ready Dependent Tasks          │     │
│  │  - For each ready task:                         │     │
│  │    1. UPDATE task_run SET status = 'RUNNING'    │     │
│  │    2. redisPublisher.publishTask(runId,         │     │
│  │                                  taskId,         │     │
│  │                                  command)        │     │
│  │                                                  │     │
│  │    XADD tasks_stream * \                        │     │
│  │      workflowRunId {runId} \                    │     │
│  │      taskId "crawl-topcv" \                     │     │
│  │      command "python topcv.py"                  │     │
│  │                                                  │     │
│  │ Step 7a: Check Workflow Completion              │     │
│  │  - SELECT status FROM task_run                  │     │
│  │    WHERE workflow_run_id = {runId}              │     │
│  │                                                  │     │
│  │  - IF all tasks status == 'SUCCESS':            │     │
│  │    1. UPDATE workflow_run SET                   │     │
│  │         status = 'COMPLETED',                   │     │
│  │         finished_at = NOW()                     │     │
│  │    2. Delete DAG from Redis:                    │     │
│  │         DEL dag:{runId}:graph                   │     │
│  │         DEL dag:{runId}:metadata                │     │
│  │    3. Log: "Workflow COMPLETED"                 │     │
│  └──────────────────────────────────────────────────┘     │
│                                                           │
│  ┌──────────── FAILURE BRANCH ─────────────────────┐     │
│  │ IF success == false:                            │     │
│  │                                                  │     │
│  │ Step 2b: Increment Retry Count                  │     │
│  │  - UPDATE task_run SET                          │     │
│  │      retry_count = retry_count + 1,             │     │
│  │      last_error = {errorPayload}                │     │
│  │    WHERE id = {taskRunId}                       │     │
│  │                                                  │     │
│  │ Step 3b: Check Retry Limit                      │     │
│  │  - IF retry_count <= max_retries:               │     │
│  │                                                  │     │
│  │    ┌─────────────────────────────────────────┐  │     │
│  │    │ RETRY: Re-enqueue Task                  │  │     │
│  │    │  - redisPublisher.publishTask(...)      │  │     │
│  │    │  - XADD tasks_stream * ...              │  │     │
│  │    │  - Log: "Retrying task (attempt 2/3)"   │  │     │
│  │    └─────────────────────────────────────────┘  │     │
│  │                                                  │     │
│  │  - ELSE (exceeded max retries):                 │     │
│  │                                                  │     │
│  │    ┌─────────────────────────────────────────┐  │     │
│  │    │ GIVE UP: Mark as FAILED                 │  │     │
│  │    │  - UPDATE task_run SET                  │  │     │
│  │    │      status = 'FAILED',                 │  │     │
│  │    │      finished_at = NOW()                │  │     │
│  │    │                                          │  │     │
│  │    │  - UPDATE workflow_run SET              │  │     │
│  │    │      status = 'FAILED',                 │  │     │
│  │    │      finished_at = NOW()                │  │     │
│  │    │                                          │  │     │
│  │    │  - Log: "Task FAILED permanently"       │  │     │
│  │    │  - Log: "Workflow FAILED"               │  │     │
│  │    └─────────────────────────────────────────┘  │     │
│  └──────────────────────────────────────────────────┘     │
└──────┬────────────────────────────────────────────────────┘
       │
       ▼
┌──────────────────────────────────────────────────────────┐
│  Response: HTTP 200 OK (empty body)                      │
└──────────────────────────────────────────────────────────┘
```

### Example Scenario: Task Success

**Initial State:**
```sql
-- task_run table
| id     | task_id        | status  | retry_count |
|--------|----------------|---------|-------------|
| tr-001 | crawl-linkedin | RUNNING | 0           |
| tr-002 | crawl-topcv    | PENDING | 0           |
```

**API Call:**
```bash
POST /api/workflows/550e8400-e29b.../tasks/crawl-linkedin/complete?success=true
```

**After Processing:**
```sql
-- task_run table
| id     | task_id        | status  | retry_count | finished_at         |
|--------|----------------|---------|-------------|---------------------|
| tr-001 | crawl-linkedin | SUCCESS | 0           | 2025-11-20 10:35:22 |
| tr-002 | crawl-topcv    | RUNNING | 0           | NULL                |
```

**Redis Streams (New Entry):**
```
tasks_stream:
  1700501000000-0  {
    workflowRunId: "550e8400-e29b-41d4-a716-446655440000",
    taskId: "crawl-topcv",
    command: "python /opt/crawlers/topcv.py"
  }
```

### Example Scenario: Task Failure with Retry

**Initial State:**
```sql
| id     | task_id     | status  | retry_count | max_retries |
|--------|-------------|---------|-------------|-------------|
| tr-001 | crawl-site  | RUNNING | 0           | 3           |
```

**API Call (1st Failure):**
```bash
POST /api/workflows/{runId}/tasks/crawl-site/complete?success=false
Body: "Connection timeout after 30 seconds"
```

**After Processing:**
```sql
| id     | task_id     | status  | retry_count | max_retries | last_error              |
|--------|-------------|---------|-------------|-------------|-------------------------|
| tr-001 | crawl-site  | RUNNING | 1           | 3           | Connection timeout...   |
```

**Redis Streams (Retry Enqueued):**
```
tasks_stream:
  1700501100000-0  {
    workflowRunId: "550e8400-e29b-41d4-a716-446655440000",
    taskId: "crawl-site",
    command: "python /opt/crawlers/site.py"
  }
```

**After 4th Failure (retry_count = 4 > max_retries = 3):**
```sql
-- task_run table
| id     | task_id     | status | retry_count | finished_at         |
|--------|-------------|--------|-------------|---------------------|
| tr-001 | crawl-site  | FAILED | 4           | 2025-11-20 10:45:00 |

-- workflow_run table
| id              | status | finished_at         |
|-----------------|--------|---------------------|
| 550e8400-e29... | FAILED | 2025-11-20 10:45:00 |
```

---

## API 3: POST /api/workflows/task/callback

### Overview
This is an **alternative callback endpoint** that accepts a JSON body instead of URL parameters. It's functionally identical to API 2 but more convenient for programmatic integrations (SDKs, worker libraries).

### Request Flow

```
┌─────────────┐
│   Worker    │
│  (Python    │
│   SDK)      │
└──────┬──────┘
       │ POST /api/workflows/task/callback
       │ Content-Type: application/json
       │ {
       │   "workflowRunId": "550e8400-e29b-41d4-a716-446655440000",
       │   "taskId": "crawl-linkedin",
       │   "success": true,
       │   "lastError": null
       │ }
       ▼
┌──────────────────────────────────────────────────────────┐
│    WorkflowController.onTaskCallback()                   │
│  - Parse JSON body to Map<String, Object>                │
│  - Extract fields:                                        │
│    • workflowRunId (String)                              │
│    • taskId (String)                                     │
│    • success (boolean)                                   │
│    • lastError (String, nullable)                        │
│  - Calls orchestrator.onTaskCompleted(...)               │
└──────┬───────────────────────────────────────────────────┘
       │
       ▼
┌──────────────────────────────────────────────────────────┐
│   WorkflowOrchestrator.onTaskCompleted()                 │
│   *** SAME LOGIC AS API 2 ***                            │
│   (See API 2 flow diagram above)                         │
└──────┬────────────────────────────────────────────────────┘
       │
       ▼
┌──────────────────────────────────────────────────────────┐
│  Response: HTTP 200 OK (empty body)                      │
└──────────────────────────────────────────────────────────┘
```

### Key Differences from API 2

| Aspect | API 2 (URL Params) | API 3 (JSON Body) |
|--------|-------------------|-------------------|
| **Endpoint** | `/api/workflows/{runId}/tasks/{taskId}/complete` | `/api/workflows/task/callback` |
| **Content-Type** | `text/plain` (optional error body) | `application/json` |
| **Parameters** | Path: `runId`, `taskId`<br>Query: `success` | JSON: all fields in body |
| **Use Case** | Simple curl commands, shell scripts | SDK integrations, structured logging |
| **Error Payload** | Request body as plain text | JSON field `lastError` |

### Example Usage (Python SDK)

```python
import requests

def report_task_completion(run_id, task_id, success, error=None):
    """Report task completion using JSON callback endpoint"""
    
    response = requests.post(
        "http://localhost:8080/api/workflows/task/callback",
        json={
            "workflowRunId": run_id,
            "taskId": task_id,
            "success": success,
            "lastError": error
        }
    )
    
    return response.status_code == 200

# Success
report_task_completion(
    run_id="550e8400-e29b-41d4-a716-446655440000",
    task_id="crawl-linkedin",
    success=True
)

# Failure
report_task_completion(
    run_id="550e8400-e29b-41d4-a716-446655440000",
    task_id="crawl-topcv",
    success=False,
    error="Rate limit exceeded: 429 Too Many Requests"
)
```

---

## Complete End-to-End Flow

### Scenario: 3-Task Workflow with Dependencies

**Workflow Definition:**
```yaml
id: simple-pipeline
name: "Simple Data Pipeline"
tasks:
  - id: fetch-data
    command: "curl https://api.example.com/data > data.json"
    maxRetries: 3
    
  - id: process-data
    command: "python process.py data.json"
    maxRetries: 2
    dependsOn:
      - fetch-data
      
  - id: upload-results
    command: "aws s3 cp results.csv s3://bucket/"
    maxRetries: 3
    dependsOn:
      - process-data
```

**Timeline:**

```
T=0s   Client submits workflow
       ↓ POST /api/workflows/start
       
T=0.1s API creates workflow_run (id: run-123)
       API creates 3 task_run records (all PENDING)
       API saves DAG to Redis:
         fetch-data    → ""
         process-data  → "fetch-data"
         upload-results → "process-data"
       
T=0.2s API enqueues root task to Redis Streams:
       XADD tasks_stream * workflowRunId "run-123" 
                           taskId "fetch-data"
                           command "curl https://..."
       
T=0.3s Worker-1 picks up fetch-data from stream
       Worker-1 executes: curl https://api.example.com/data
       
T=5s   Worker-1 completes successfully
       ↓ POST /api/workflows/run-123/tasks/fetch-data/complete?success=true
       
T=5.1s API updates task_run: fetch-data → SUCCESS
       API loads DAG from Redis
       API finds dependent: process-data
       API checks dependencies: fetch-data = SUCCESS ✓
       API enqueues process-data to Redis Streams
       
T=5.2s Worker-2 picks up process-data from stream
       Worker-2 executes: python process.py data.json
       
T=10s  Worker-2 completes successfully
       ↓ POST /api/workflows/task/callback
       {
         "workflowRunId": "run-123",
         "taskId": "process-data",
         "success": true,
         "lastError": null
       }
       
T=10.1s API updates task_run: process-data → SUCCESS
        API finds dependent: upload-results
        API checks dependencies: process-data = SUCCESS ✓
        API enqueues upload-results to Redis Streams
        
T=10.2s Worker-3 picks up upload-results from stream
        Worker-3 executes: aws s3 cp results.csv...
        
T=15s   Worker-3 completes successfully
        ↓ POST /api/workflows/run-123/tasks/upload-results/complete?success=true
        
T=15.1s API updates task_run: upload-results → SUCCESS
        API checks all tasks: ALL SUCCESS ✓
        API updates workflow_run: status → COMPLETED
        API deletes DAG from Redis
        
        🎉 WORKFLOW COMPLETED
```

### Database State Evolution

**T=0.2s (After Workflow Start):**
```sql
-- workflow_run
| id      | status  | started_at          | finished_at |
|---------|---------|---------------------|-------------|
| run-123 | RUNNING | 2025-11-20 10:00:00 | NULL        |

-- task_run
| id   | task_id        | status  | started_at | finished_at |
|------|----------------|---------|------------|-------------|
| t-1  | fetch-data     | PENDING | NULL       | NULL        |
| t-2  | process-data   | PENDING | NULL       | NULL        |
| t-3  | upload-results | PENDING | NULL       | NULL        |
```

**T=5.1s (After fetch-data Completes):**
```sql
-- task_run
| id   | task_id        | status  | started_at          | finished_at         |
|------|----------------|---------|---------------------|---------------------|
| t-1  | fetch-data     | SUCCESS | 2025-11-20 10:00:00 | 2025-11-20 10:00:05 |
| t-2  | process-data   | RUNNING | NULL                | NULL                |
| t-3  | upload-results | PENDING | NULL                | NULL                |
```

**T=15.1s (After All Tasks Complete):**
```sql
-- workflow_run
| id      | status    | started_at          | finished_at         |
|---------|-----------|---------------------|---------------------|
| run-123 | COMPLETED | 2025-11-20 10:00:00 | 2025-11-20 10:00:15 |

-- task_run
| id   | task_id        | status  | started_at          | finished_at         |
|------|----------------|---------|---------------------|---------------------|
| t-1  | fetch-data     | SUCCESS | 2025-11-20 10:00:00 | 2025-11-20 10:00:05 |
| t-2  | process-data   | SUCCESS | 2025-11-20 10:00:05 | 2025-11-20 10:00:10 |
| t-3  | upload-results | SUCCESS | 2025-11-20 10:00:10 | 2025-11-20 10:00:15 |
```

---

## Database State Transitions

### TaskRun Status Lifecycle

```
                    ┌──────────┐
                    │ PENDING  │ ← Created when workflow starts
                    └────┬─────┘
                         │
                         │ Dependencies satisfied
                         │ → Enqueued to Redis Streams
                         ▼
                    ┌──────────┐
              ┌────>│ RUNNING  │
              │     └────┬─────┘
              │          │
              │          ├──── success=true ────────┐
              │          │                          ▼
              │          │                     ┌──────────┐
              │          │                     │ SUCCESS  │ (Terminal)
              │          │                     └──────────┘
              │          │
              │          └──── success=false ───┐
              │                                 ▼
              │                            ┌──────────┐
              │                            │  Check   │
              │                            │  Retry   │
              │                            │  Count   │
              │                            └────┬─────┘
              │                                 │
              │     ┌──── retry_count <= max_retries
              └─────┘     (re-enqueue to stream)
                    │
                    └──── retry_count > max_retries
                                 │
                                 ▼
                            ┌──────────┐
                            │  FAILED  │ (Terminal)
                            └──────────┘
```

### WorkflowRun Status Lifecycle

```
                    ┌──────────┐
                    │ RUNNING  │ ← Created when workflow starts
                    └────┬─────┘
                         │
                         ├──── All tasks SUCCESS ────────┐
                         │                               ▼
                         │                          ┌───────────┐
                         │                          │ COMPLETED │ (Terminal)
                         │                          └───────────┘
                         │
                         └──── Any task FAILED ─────┐
                                                     ▼
                                                ┌──────────┐
                                                │  FAILED  │ (Terminal)
                                                └──────────┘
```

---

## Redis Data Structures

### 1. DAG Graph Storage

**Key Pattern:** `dag:{workflowRunId}:graph`  
**Type:** Hash  
**Purpose:** Store task dependency graph

**Example:**
```redis
HGETALL dag:550e8400-e29b-41d4-a716-446655440000:graph

1) "crawl-linkedin"
2) ""                           # No dependencies (root task)
3) "crawl-topcv"
4) "crawl-linkedin"             # Depends on crawl-linkedin
5) "crawl-itviec"
6) "crawl-linkedin"             # Depends on crawl-linkedin
7) "ai-extract"
8) "crawl-linkedin,crawl-topcv,crawl-itviec"  # Multiple dependencies
9) "store-db"
10) "ai-extract"
```

### 2. DAG Metadata

**Key Pattern:** `dag:{workflowRunId}:metadata`  
**Type:** Hash  
**Purpose:** Store workflow execution metadata

**Example:**
```redis
HGETALL dag:550e8400-e29b-41d4-a716-446655440000:metadata

1) "status"
2) "RUNNING"
3) "createdAt"
4) "2025-11-20T10:30:00"
```

### 3. Task Queue (Redis Streams)

**Key:** `tasks_stream`  
**Type:** Stream  
**Purpose:** Distribute tasks to workers

**Example:**
```redis
XRANGE tasks_stream - + COUNT 5

1) 1700500800000-0
   1) "workflowRunId"
   2) "550e8400-e29b-41d4-a716-446655440000"
   3) "taskId"
   4) "crawl-linkedin"
   5) "command"
   6) "python /opt/crawlers/linkedin.py --max-pages 10"

2) 1700500805000-0
   1) "workflowRunId"
   2) "550e8400-e29b-41d4-a716-446655440000"
   3) "taskId"
   4) "crawl-topcv"
   5) "command"
   6) "python /opt/crawlers/topcv.py --max-pages 10"
```

### 4. Lifecycle

```
Workflow Start (API 1):
  ↓
  HMSET dag:{runId}:graph ...
  HMSET dag:{runId}:metadata ...
  XADD tasks_stream * ...

Task Completion (API 2/3):
  ↓
  HGETALL dag:{runId}:graph  (load dependencies)
  ↓
  Check dependencies satisfied
  ↓
  XADD tasks_stream * ...  (enqueue ready tasks)

Workflow Completion:
  ↓
  DEL dag:{runId}:graph
  DEL dag:{runId}:metadata
```

---

## Performance Characteristics

### API 1: POST /api/workflows/start

**Latency:** 50-200ms (depends on task count)

**Database Operations:**
- 1 INSERT into `workflow_entity`
- 1 INSERT into `workflow_run`
- N INSERTs into `task_run` (N = number of tasks)

**Redis Operations:**
- 1 HMSET for DAG graph (O(N) where N = tasks)
- 1 HMSET for metadata (O(1))
- M XADDs for root tasks (M = number of root tasks)

**Bottlenecks:**
- Large workflows (100+ tasks): Consider batching task_run inserts
- Complex DAGs: Graph validation can take time

### API 2/3: Task Completion Callbacks

**Latency:** 10-50ms (typical), up to 200ms (if many dependents)

**Database Operations:**
- 1 SELECT (find task_run)
- 1 UPDATE (update task_run status)
- N SELECTs (check N dependencies for each dependent)
- M UPDATEs (update M dependent tasks to RUNNING)
- 0-1 UPDATE (workflow_run status if completed/failed)

**Redis Operations:**
- 1 HGETALL (load DAG graph)
- M XADDs (enqueue M ready dependent tasks)
- 0-2 DELs (cleanup DAG if workflow completed)

**Bottlenecks:**
- Task with many dependents: Each dependent triggers dependency check
- High task completion rate: Database UPDATE contention

---

## Error Handling & Edge Cases

### Duplicate Callback

**Scenario:** Worker calls callback twice for same task

**Behavior:**
- First call: Task marked SUCCESS, dependents enqueued
- Second call: Task already SUCCESS, no-op (idempotent)
- No duplicate task enqueueing (status check prevents re-enqueue)

### Worker Crash After Task Success

**Scenario:** Worker completes task but crashes before calling callback

**Mitigation:**
- Workers should use Redis Streams consumer groups with acknowledgment
- Pending messages can be claimed by other workers
- Timeout detection (future feature)

### Partial Dependency Failure

**Scenario:** Task A and B succeed, Task C depends on both, then Task A fails on retry

**Behavior:**
- Task C will never run (dependency not satisfied)
- Workflow remains RUNNING indefinitely (needs timeout/cancellation API)

---

## Summary

### API 1: POST /api/workflows/start
✅ **Purpose:** Submit new workflow  
✅ **Input:** YAML/JSON definition  
✅ **Output:** Workflow run ID  
✅ **Side Effects:**
- Creates workflow, run, and task records in PostgreSQL
- Caches DAG in Redis
- Enqueues root tasks to Redis Streams

### API 2: POST /api/workflows/{runId}/tasks/{taskId}/complete
✅ **Purpose:** Worker callback (URL params)  
✅ **Input:** Path params + query param + optional body  
✅ **Output:** 200 OK  
✅ **Side Effects:**
- Updates task status
- Enqueues dependent tasks if ready
- Marks workflow complete/failed if all tasks done

### API 3: POST /api/workflows/task/callback
✅ **Purpose:** Worker callback (JSON body)  
✅ **Input:** JSON with all fields  
✅ **Output:** 200 OK  
✅ **Side Effects:** Same as API 2

---

**Next Steps:**
- Implement workflow cancellation: `POST /api/workflows/{id}/cancel`
- Implement manual retry: `POST /api/workflows/{id}/tasks/{taskId}/retry`
- Add timeout detection for stuck workflows
- Add WebSocket support for real-time updates

**Document Version:** 1.0  
**Last Updated:** November 20, 2025

