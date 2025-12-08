# Job Scheduler - API Testing Guide

## 📋 Project Overview

### Architecture
This is a **Distributed DAG (Directed Acyclic Graph) Job Scheduler** built with:
- **API Service** (Port 8080): Orchestrates workflow execution, manages state
- **Worker Service** (Port 8081): Executes shell commands from tasks
- **PostgreSQL**: Persists workflow definitions, runs, and task execution state
- **Redis Streams**: Message queue for distributing tasks to workers

### How It Works
1. **Submit Workflow**: POST YAML/JSON workflow definition to API service
2. **Parse & Persist**: API service parses DAG, stores workflow and creates task runs
3. **Enqueue Root Tasks**: Tasks with no dependencies are published to Redis stream
4. **Workers Execute**: Worker service consumes tasks, executes shell commands
5. **Callback**: Workers report success/failure back to orchestrator
6. **Schedule Dependents**: When all parent tasks succeed, child tasks are enqueued
7. **Complete**: Workflow completes when all tasks finish successfully

---

## 🧪 API Endpoints

### 1. Start Workflow
**Endpoint:** `POST /api/workflows/start`  
**Content-Type:** `text/plain`  
**Body:** YAML or JSON workflow definition

### 2. Task Completion Callback
**Endpoint:** `POST /api/workflows/task/callback`  
**Content-Type:** `application/json`  
**Body:** Task completion payload (used by workers)

### 3. Manual Task Completion (Testing)
**Endpoint:** `POST /api/workflows/{runId}/tasks/{taskId}/complete`  
**Query Params:** `success` (boolean), optional `errorPayload`

---

## 🚀 cURL Testing Examples

## Example 1: Simple Linear Workflow

### Start Workflow
```bash
curl -X POST http://localhost:8080/api/workflows/start \
  -H "Content-Type: text/plain" \
  -d 'name: simple_test
description: Simple 3-step workflow
tasks:
  - id: task1
    name: First Task
    command: echo "Task 1 starting" && sleep 2 && echo "Task 1 complete"
  - id: task2
    name: Second Task
    depends_on:
      - task1
    command: echo "Task 2 starting" && sleep 2 && echo "Task 2 complete"
  - id: task3
    name: Third Task
    depends_on:
      - task2
    command: echo "Task 3 starting" && sleep 1 && echo "Task 3 complete"'
```

**Expected Response:**
```json
{
  "workflowRunId": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
}
```

---

## Example 2: Daily Backup Workflow (Complex DAG)

### Start Workflow
```bash
curl -X POST http://localhost:8080/api/workflows/start \
  -H "Content-Type: text/plain" \
  -d 'name: database_backup_workflow
description: Backup databases and upload to cloud storage
tasks:
  - id: backup_postgres
    name: Backup PostgreSQL Database
    command: echo "Backing up database..." && sleep 3 && echo "Backup created"
    max_retries: 2

  - id: compress_backup
    name: Compress Backup File
    depends_on:
      - backup_postgres
    command: echo "Compressing backup..." && sleep 2 && echo "Backup compressed"
    max_retries: 1

  - id: upload_to_s3
    name: Upload to AWS S3
    depends_on:
      - compress_backup
    command: echo "Uploading to S3..." && sleep 2 && echo "Upload complete"
    max_retries: 3

  - id: verify_backup
    name: Verify Backup Integrity
    depends_on:
      - upload_to_s3
    command: echo "Verifying backup..." && sleep 1 && echo "Backup verified"
    max_retries: 2

  - id: cleanup_old_backups
    name: Remove Local Backup
    depends_on:
      - verify_backup
    command: echo "Cleaning up..." && sleep 1 && echo "Cleanup done"
    max_retries: 1

  - id: send_report
    name: Email Backup Report
    depends_on:
      - cleanup_old_backups
    command: echo "Sending report..." && sleep 1 && echo "Report sent"'
```

**Expected Response:**
```json
{
  "workflowRunId": "f7e6d5c4-b3a2-4192-8374-5a6b7c8d9e0f"
}
```

---

## Example 3: Parallel Execution (Diamond DAG)

### Start Workflow
```bash
curl -X POST http://localhost:8080/api/workflows/start \
  -H "Content-Type: text/plain" \
  -d 'name: parallel_processing
description: Process data in parallel then merge
tasks:
  - id: start
    name: Initialize
    command: echo "Starting parallel workflow" && date

  - id: process_a
    name: Process A
    depends_on:
      - start
    command: echo "Processing A..." && sleep 3 && echo "A done"

  - id: process_b
    name: Process B
    depends_on:
      - start
    command: echo "Processing B..." && sleep 3 && echo "B done"

  - id: process_c
    name: Process C
    depends_on:
      - start
    command: echo "Processing C..." && sleep 3 && echo "C done"

  - id: merge
    name: Merge Results
    depends_on:
      - process_a
      - process_b
      - process_c
    command: echo "Merging results..." && sleep 2 && echo "Merge complete"'
```

**Expected Response:**
```json
{
  "workflowRunId": "9a8b7c6d-5e4f-4321-ba98-76543210fedc"
}
```

---

## Example 4: ETL Pipeline

### Start Workflow
```bash
curl -X POST http://localhost:8080/api/workflows/start \
  -H "Content-Type: text/plain" \
  -d 'name: daily_etl
description: Daily ETL pipeline
tasks:
  - id: extract
    name: Extract data
    command: echo "Extracting data from sources..." && sleep 2 && echo "Extract complete"
    
  - id: transform
    name: Transform
    depends_on:
      - extract
    command: echo "Transforming data..." && sleep 3 && echo "Transform complete"
    
  - id: load
    name: Load
    depends_on:
      - transform
    command: echo "Loading data to warehouse..." && sleep 2 && echo "Load complete"
    
  - id: notify
    name: Notify
    depends_on:
      - load
    command: echo "ETL pipeline completed successfully"'
```

**Expected Response:**
```json
{
  "workflowRunId": "1234abcd-5678-90ef-ghij-klmnopqrstuv"
}
```

---

## Example 5: Error Handling & Retry

### Start Workflow with Intentional Failure
```bash
curl -X POST http://localhost:8080/api/workflows/start \
  -H "Content-Type: text/plain" \
  -d 'name: retry_test
description: Test retry mechanism
tasks:
  - id: reliable_task
    name: Reliable Task
    command: echo "This will succeed" && sleep 1
    
  - id: flaky_task
    name: Flaky Task (will fail)
    depends_on:
      - reliable_task
    command: exit 1
    max_retries: 3
    
  - id: final_task
    name: Final Task
    depends_on:
      - flaky_task
    command: echo "This will not run if flaky_task fails"'
```

**Expected Response:**
```json
{
  "workflowRunId": "abcd1234-efgh-5678-ijkl-mnopqrstuvwx"
}
```

---

## Example 6: Manual Task Completion (Testing)

### Complete a Task Manually
```bash
# Success
curl -X POST "http://localhost:8080/api/workflows/{workflowRunId}/tasks/{taskId}/complete?success=true"

# Failure
curl -X POST "http://localhost:8080/api/workflows/{workflowRunId}/tasks/{taskId}/complete?success=false" \
  -H "Content-Type: text/plain" \
  -d "Task failed due to network timeout"
```

**Expected Response:**
```
HTTP/1.1 200 OK
```

---

## Example 7: Worker Callback (Internal API)

### Simulate Worker Reporting Task Completion
```bash
curl -X POST http://localhost:8080/api/workflows/task/callback \
  -H "Content-Type: application/json" \
  -d '{
    "workflowRunId": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
    "taskId": "task1",
    "success": true,
    "lastError": null
  }'
```

**Expected Response:**
```
HTTP/1.1 200 OK
```

---

## 📊 Checking Workflow Status

Since the API currently doesn't have GET endpoints, you can check status via:

### Option 1: Check PostgreSQL Database
```bash
# Connect to PostgreSQL
docker exec -it job_postgres psql -U jobuser -d jobdb

# Check workflow runs
SELECT id, status, started_at, finished_at FROM workflow_runs ORDER BY started_at DESC LIMIT 5;

# Check task runs for a specific workflow
SELECT task_id, task_name, status, retry_count, last_error, started_at, finished_at 
FROM task_runs 
WHERE workflow_run_id = 'YOUR_WORKFLOW_RUN_ID'
ORDER BY started_at;
```

### Option 2: Check Application Logs
```bash
# API Service logs
tail -f api-service.log

# Worker Service logs
tail -f worker-service.log
```

### Option 3: Check Redis Stream
```bash
# Connect to Redis
docker exec -it job_redis redis-cli

# Check pending messages in stream
XPENDING tasks_stream worker-group

# Read messages from stream
XREAD COUNT 10 STREAMS tasks_stream 0
```

---

## 🔍 Expected Workflow Execution Flow

### Timeline for Simple Linear Workflow:

```
t=0s    → POST /api/workflows/start
          ✓ Response: { "workflowRunId": "abc-123" }
          
t=0.1s  → task1 enqueued to Redis stream
          
t=0.2s  → Worker picks up task1
          ▶ Status: task1=RUNNING
          
t=2.2s  → task1 completes successfully
          ✓ Worker calls back: success=true
          ▶ Status: task1=SUCCESS
          → task2 enqueued to Redis stream
          
t=2.3s  → Worker picks up task2
          ▶ Status: task2=RUNNING
          
t=4.3s  → task2 completes successfully
          ✓ Worker calls back: success=true
          ▶ Status: task2=SUCCESS
          → task3 enqueued to Redis stream
          
t=4.4s  → Worker picks up task3
          ▶ Status: task3=RUNNING
          
t=5.4s  → task3 completes successfully
          ✓ Worker calls back: success=true
          ▶ Status: task3=SUCCESS
          
t=5.5s  → All tasks complete
          ✓ WorkflowRun status: COMPLETED
```

---

## ⚠️ Error Responses

### Invalid YAML/JSON
```bash
curl -X POST http://localhost:8080/api/workflows/start \
  -H "Content-Type: text/plain" \
  -d 'invalid yaml content: [ broken'
```

**Response:**
```json
{
  "code": "parse_error",
  "message": "while parsing a flow sequence\n in 'reader', line 1, column 22:\n    invalid yaml content: [ broken\n                         ^\nexpected ',' or ']', but got StreamEnd\n in 'reader', line 1, column 30:\n    invalid yaml content: [ broken\n                                 ^\n"
}
```

### Cyclic Dependency
```bash
curl -X POST http://localhost:8080/api/workflows/start \
  -H "Content-Type: text/plain" \
  -d 'name: cyclic_test
tasks:
  - id: task1
    depends_on:
      - task2
    command: echo "task1"
  - id: task2
    depends_on:
      - task1
    command: echo "task2"'
```

**Response:**
```json
{
  "code": "parse_error",
  "message": "cycles are not allowed"
}
```

---

## 🎯 Testing Checklist

- [ ] **Test 1:** Simple linear workflow (3 tasks)
- [ ] **Test 2:** Parallel execution (diamond DAG)
- [ ] **Test 3:** Complex multi-level DAG (backup workflow)
- [ ] **Test 4:** Task retry on failure
- [ ] **Test 5:** Workflow fails if task permanently fails
- [ ] **Test 6:** Invalid YAML error handling
- [ ] **Test 7:** Cyclic dependency detection
- [ ] **Test 8:** Multiple concurrent workflows
- [ ] **Test 9:** Worker scaling (run multiple worker instances)
- [ ] **Test 10:** Database persistence (restart services)

---

## 🛠️ Troubleshooting

### Workers not picking up tasks
```bash
# Check Redis stream has consumer group
docker exec -it job_redis redis-cli
XINFO GROUPS tasks_stream

# Expected output:
# 1) "name": "worker-group"
```

### Tasks stuck in RUNNING
```bash
# Check worker service is running
ps aux | grep worker-service

# Check worker logs
tail -f worker-service.log
```

### Database connection issues
```bash
# Test PostgreSQL connection
docker exec -it job_postgres psql -U jobuser -d jobdb -c "SELECT 1"

# Check API service application.properties
cat api-service/src/main/resources/application.properties
```

---

## 📈 Performance Notes

- **Parallel Execution:** Tasks with no dependencies run concurrently
- **Redis Streams:** Supports multiple worker instances (consumer groups)
- **Database:** PostgreSQL stores full execution history
- **Retry Logic:** Configurable per task with exponential backoff
- **Scalability:** Add more worker instances to handle higher load

---

## 🔄 Next Steps

Based on the plan.md, upcoming features include:
- REST API v2 with GET endpoints (list/query workflows)
- Web dashboard with real-time monitoring
- HTTP executor (call REST APIs instead of shell commands)
- Python executor, SQL executor
- Scheduling (cron-based workflow triggers)
- Webhook notifications
- Workflow versioning

---

**Generated:** 2025-11-12  
**Project:** Distributed Job Scheduler v1.0.0

