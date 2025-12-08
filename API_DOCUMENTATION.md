# Job Scheduler & Orchestration Platform - API Documentation

**Version:** 1.0.0  
**Base URL:** `http://localhost:8080`  
**Date:** November 20, 2025

---

## Table of Contents

1. [Overview](#overview)
2. [Authentication](#authentication)
3. [Workflow Management APIs](#workflow-management-apis)
4. [Workflow Query APIs](#workflow-query-apis)
5. [Task Management APIs](#task-management-apis)
6. [Response Models](#response-models)
7. [Error Handling](#error-handling)
8. [Examples](#examples)

---

## Overview

The Job Scheduler & Orchestration Platform provides REST APIs to:
- Submit and execute multi-step workflows defined in YAML/JSON
- Monitor workflow execution status in real-time
- Query workflow history with pagination and filtering
- Track individual task execution within workflows
- Handle task completion callbacks from worker services

**Key Features:**
- YAML/JSON-based workflow definitions
- Dependency-based task execution (DAG support)
- Redis Streams for distributed task queue
- PostgreSQL for workflow state persistence
- Multi-worker scaling support

---

## Authentication

**Current Status:** ⚠️ No authentication required (internal tool)

**Planned:** OAuth2/SSO integration planned for Week 9+ (post-core features)

---

## Workflow Management APIs

### 1. Start Workflow

**Endpoint:** `POST /api/workflows/start`

**Description:** Submit a new workflow for execution. The workflow definition can be in YAML or JSON format.

**Request:**
- **Content-Type:** `text/plain`
- **Body:** YAML or JSON workflow definition (raw text)

**Response:**
- **Status:** `200 OK`
- **Body:**
```json
{
  "workflowRunId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Error Response:**
- **Status:** `400 Bad Request`
- **Body:**
```json
{
  "code": "parse_error",
  "message": "Invalid YAML: ..."
}
```

**Example Request:**
```bash
curl -X POST http://localhost:8080/api/workflows/start \
  -H "Content-Type: text/plain" \
  --data-binary @workflow.yaml
```

**Example Workflow (YAML):**
```yaml
id: job-market-crawler
name: "Job Market Data Crawler"
description: "Crawl job listings from LinkedIn, TopCV, ITviec"

tasks:
  - id: crawl-linkedin
    name: "Crawl LinkedIn Jobs"
    command: "python /opt/crawlers/linkedin.py --max-pages 10"
    maxRetries: 3
    timeoutSeconds: 300

  - id: crawl-topcv
    name: "Crawl TopCV Jobs"
    command: "python /opt/crawlers/topcv.py --max-pages 10"
    maxRetries: 3
    timeoutSeconds: 300
    dependsOn:
      - crawl-linkedin

  - id: ai-extract
    name: "AI Data Extraction"
    command: "python /opt/crawlers/ai_extract.py"
    maxRetries: 2
    timeoutSeconds: 600
    dependsOn:
      - crawl-linkedin
      - crawl-topcv

  - id: store-to-db
    name: "Store to Database"
    command: "python /opt/crawlers/store_db.py"
    maxRetries: 3
    timeoutSeconds: 120
    dependsOn:
      - ai-extract
```

---

### 2. Task Completion Callback (Path Parameters)

**Endpoint:** `POST /api/workflows/{runId}/tasks/{taskId}/complete`

**Description:** Worker callback endpoint to mark a task as completed or failed.

**Path Parameters:**
- `runId` (string, required): Workflow run ID
- `taskId` (string, required): Task ID from workflow definition

**Query Parameters:**
- `success` (boolean, default: true): Whether task succeeded

**Request Body (optional):**
- **Content-Type:** `text/plain`
- **Body:** Error message/payload (if `success=false`)

**Response:**
- **Status:** `200 OK`

**Example Request (Success):**
```bash
curl -X POST "http://localhost:8080/api/workflows/550e8400-e29b-41d4-a716-446655440000/tasks/crawl-linkedin/complete?success=true"
```

**Example Request (Failure):**
```bash
curl -X POST "http://localhost:8080/api/workflows/550e8400-e29b-41d4-a716-446655440000/tasks/crawl-linkedin/complete?success=false" \
  -H "Content-Type: text/plain" \
  -d "Connection timeout after 30 seconds"
```

---

### 3. Task Completion Callback (JSON Body)

**Endpoint:** `POST /api/workflows/task/callback`

**Description:** Alternative callback endpoint using JSON body (for programmatic integrations).

**Request:**
- **Content-Type:** `application/json`
- **Body:**
```json
{
  "workflowRunId": "550e8400-e29b-41d4-a716-446655440000",
  "taskId": "crawl-linkedin",
  "success": true,
  "lastError": null
}
```

**Response:**
- **Status:** `200 OK`

**Example Request:**
```bash
curl -X POST http://localhost:8080/api/workflows/task/callback \
  -H "Content-Type: application/json" \
  -d '{
    "workflowRunId": "550e8400-e29b-41d4-a716-446655440000",
    "taskId": "crawl-linkedin",
    "success": false,
    "lastError": "Rate limit exceeded"
  }'
```

---

## Workflow Query APIs

### 4. List All Workflows

**Endpoint:** `GET /api/workflows`

**Description:** Get a paginated list of all workflow runs with optional status filtering.

**Query Parameters:**
- `page` (integer, default: 0): Page number (zero-indexed)
- `size` (integer, default: 20): Number of items per page
- `status` (string, optional): Filter by status (`RUNNING`, `COMPLETED`, `FAILED`)

**Response:**
- **Status:** `200 OK`
- **Body:**
```json
{
  "content": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "workflowId": "job-market-crawler",
      "workflowName": "Job Market Data Crawler",
      "status": "RUNNING",
      "startedAt": "2025-11-20T10:30:00",
      "finishedAt": null,
      "durationSeconds": null
    },
    {
      "id": "660e8400-e29b-41d4-a716-446655440001",
      "workflowId": "daily-backup",
      "workflowName": "Daily Database Backup",
      "status": "COMPLETED",
      "startedAt": "2025-11-20T02:00:00",
      "finishedAt": "2025-11-20T02:15:32",
      "durationSeconds": 932
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20
  },
  "totalPages": 5,
  "totalElements": 97,
  "last": false,
  "first": true,
  "numberOfElements": 20
}
```

**Example Requests:**
```bash
# Get first page (20 items)
curl http://localhost:8080/api/workflows

# Get page 2 with 10 items
curl "http://localhost:8080/api/workflows?page=1&size=10"

# Filter by status
curl "http://localhost:8080/api/workflows?status=RUNNING"

# Filter failed workflows on page 0
curl "http://localhost:8080/api/workflows?status=FAILED&page=0&size=50"
```

---

### 5. Get Workflow Details

**Endpoint:** `GET /api/workflows/{id}`

**Description:** Get detailed information about a specific workflow run, including task statistics and full task list.

**Path Parameters:**
- `id` (string, required): Workflow run ID

**Response:**
- **Status:** `200 OK`
- **Body:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "workflowId": "job-market-crawler",
  "workflowName": "Job Market Data Crawler",
  "workflowDescription": "Crawl job listings from LinkedIn, TopCV, ITviec",
  "status": "RUNNING",
  "startedAt": "2025-11-20T10:30:00",
  "finishedAt": null,
  "durationSeconds": null,
  "totalTasks": 4,
  "completedTasks": 2,
  "failedTasks": 0,
  "runningTasks": 1,
  "pendingTasks": 1,
  "tasks": [
    {
      "id": "task-001",
      "taskId": "crawl-linkedin",
      "taskName": "Crawl LinkedIn Jobs",
      "command": "python /opt/crawlers/linkedin.py --max-pages 10",
      "status": "SUCCESS",
      "retryCount": 0,
      "maxRetries": 3,
      "startedAt": "2025-11-20T10:30:05",
      "finishedAt": "2025-11-20T10:35:22",
      "durationSeconds": 317,
      "lastError": null
    },
    {
      "id": "task-002",
      "taskId": "crawl-topcv",
      "taskName": "Crawl TopCV Jobs",
      "command": "python /opt/crawlers/topcv.py --max-pages 10",
      "status": "SUCCESS",
      "retryCount": 0,
      "maxRetries": 3,
      "startedAt": "2025-11-20T10:35:25",
      "finishedAt": "2025-11-20T10:40:18",
      "durationSeconds": 293,
      "lastError": null
    },
    {
      "id": "task-003",
      "taskId": "ai-extract",
      "taskName": "AI Data Extraction",
      "command": "python /opt/crawlers/ai_extract.py",
      "status": "RUNNING",
      "retryCount": 0,
      "maxRetries": 2,
      "startedAt": "2025-11-20T10:40:20",
      "finishedAt": null,
      "durationSeconds": null,
      "lastError": null
    },
    {
      "id": "task-004",
      "taskId": "store-to-db",
      "taskName": "Store to Database",
      "command": "python /opt/crawlers/store_db.py",
      "status": "PENDING",
      "retryCount": 0,
      "maxRetries": 3,
      "startedAt": null,
      "finishedAt": null,
      "durationSeconds": null,
      "lastError": null
    }
  ]
}
```

**Error Response (Not Found):**
- **Status:** `404 Not Found`
- **Body:**
```json
{
  "message": "Workflow run not found: 550e8400-e29b-41d4-a716-446655440000"
}
```

**Example Request:**
```bash
curl http://localhost:8080/api/workflows/550e8400-e29b-41d4-a716-446655440000
```

---

### 6. Get Workflow Tasks

**Endpoint:** `GET /api/workflows/{id}/tasks`

**Description:** Get all tasks for a specific workflow run (without workflow metadata).

**Path Parameters:**
- `id` (string, required): Workflow run ID

**Response:**
- **Status:** `200 OK`
- **Body:**
```json
[
  {
    "id": "task-001",
    "taskId": "crawl-linkedin",
    "taskName": "Crawl LinkedIn Jobs",
    "command": "python /opt/crawlers/linkedin.py --max-pages 10",
    "status": "SUCCESS",
    "retryCount": 0,
    "maxRetries": 3,
    "startedAt": "2025-11-20T10:30:05",
    "finishedAt": "2025-11-20T10:35:22",
    "durationSeconds": 317,
    "lastError": null
  },
  {
    "id": "task-002",
    "taskId": "crawl-topcv",
    "taskName": "Crawl TopCV Jobs",
    "command": "python /opt/crawlers/topcv.py --max-pages 10",
    "status": "FAILED",
    "retryCount": 2,
    "maxRetries": 3,
    "startedAt": "2025-11-20T10:35:25",
    "finishedAt": "2025-11-20T10:36:18",
    "durationSeconds": 53,
    "lastError": "Connection timeout after 30 seconds"
  }
]
```

**Error Response (Not Found):**
- **Status:** `404 Not Found`
- **Body:**
```json
{
  "message": "Workflow run not found: 550e8400-e29b-41d4-a716-446655440000"
}
```

**Example Request:**
```bash
curl http://localhost:8080/api/workflows/550e8400-e29b-41d4-a716-446655440000/tasks
```

---

## Response Models

### WorkflowRunDTO

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Unique workflow run ID (UUID) |
| `workflowId` | string | Workflow definition ID |
| `workflowName` | string | Human-readable workflow name |
| `status` | string | Current status: `RUNNING`, `COMPLETED`, `FAILED` |
| `startedAt` | string (ISO 8601) | Workflow start timestamp |
| `finishedAt` | string (ISO 8601) | Workflow finish timestamp (null if running) |
| `durationSeconds` | integer | Total execution time in seconds (null if running) |

### WorkflowRunDetailDTO

Extends `WorkflowRunDTO` with additional fields:

| Field | Type | Description |
|-------|------|-------------|
| `workflowDescription` | string | Workflow description from YAML |
| `totalTasks` | integer | Total number of tasks |
| `completedTasks` | integer | Number of successfully completed tasks |
| `failedTasks` | integer | Number of failed tasks |
| `runningTasks` | integer | Number of currently running tasks |
| `pendingTasks` | integer | Number of pending tasks |
| `tasks` | array | List of `TaskRunDTO` objects |

### TaskRunDTO

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Unique task run ID |
| `taskId` | string | Task ID from workflow definition |
| `taskName` | string | Human-readable task name |
| `command` | string | Command to execute |
| `status` | string | Task status: `PENDING`, `RUNNING`, `SUCCESS`, `FAILED` |
| `retryCount` | integer | Number of retry attempts |
| `maxRetries` | integer | Maximum allowed retries |
| `startedAt` | string (ISO 8601) | Task start timestamp |
| `finishedAt` | string (ISO 8601) | Task finish timestamp |
| `durationSeconds` | integer | Task execution time in seconds |
| `lastError` | string | Error message (null if successful) |

### Page Response

Standard Spring Data pagination wrapper:

| Field | Type | Description |
|-------|------|-------------|
| `content` | array | Array of items for current page |
| `pageable.pageNumber` | integer | Current page number (0-indexed) |
| `pageable.pageSize` | integer | Items per page |
| `totalPages` | integer | Total number of pages |
| `totalElements` | integer | Total number of items across all pages |
| `first` | boolean | True if this is the first page |
| `last` | boolean | True if this is the last page |
| `numberOfElements` | integer | Number of items in current page |

---

## Error Handling

### Error Response Format

All errors return a consistent JSON structure:

```json
{
  "message": "Human-readable error description",
  "code": "error_code" // (optional, for parse errors)
}
```

### HTTP Status Codes

| Status Code | Description | Example |
|-------------|-------------|---------|
| `200 OK` | Request succeeded | Workflow started, query returned results |
| `400 Bad Request` | Invalid request | Malformed YAML, invalid parameters |
| `404 Not Found` | Resource not found | Workflow run ID doesn't exist |
| `500 Internal Server Error` | Server error | Database connection failure |

### Common Error Scenarios

**1. Invalid Workflow YAML**
```json
{
  "code": "parse_error",
  "message": "Invalid YAML: mapping values are not allowed here..."
}
```

**2. Workflow Not Found**
```json
{
  "message": "Workflow run not found: 550e8400-e29b-41d4-a716-446655440000"
}
```

---

## Examples

### Use Case 1: Job Market Crawler Workflow

**Step 1: Submit Workflow**
```bash
cat > job-crawler.yaml << 'EOF'
id: job-market-crawler
name: "Daily Job Market Crawler"
description: "Crawl LinkedIn, TopCV, ITviec and extract job data using AI"

tasks:
  - id: crawl-linkedin
    name: "Crawl LinkedIn"
    command: "python /opt/crawlers/linkedin.py --max-pages 10"
    maxRetries: 3
    timeoutSeconds: 300

  - id: crawl-topcv
    name: "Crawl TopCV"
    command: "python /opt/crawlers/topcv.py --max-pages 10"
    maxRetries: 3
    timeoutSeconds: 300

  - id: crawl-itviec
    name: "Crawl ITviec"
    command: "python /opt/crawlers/itviec.py --max-pages 10"
    maxRetries: 3
    timeoutSeconds: 300

  - id: ai-extract-linkedin
    name: "AI Extract LinkedIn Data"
    command: "python /opt/crawlers/ai_extract.py --source linkedin"
    maxRetries: 2
    timeoutSeconds: 600
    dependsOn:
      - crawl-linkedin

  - id: ai-extract-topcv
    name: "AI Extract TopCV Data"
    command: "python /opt/crawlers/ai_extract.py --source topcv"
    maxRetries: 2
    timeoutSeconds: 600
    dependsOn:
      - crawl-topcv

  - id: ai-extract-itviec
    name: "AI Extract ITviec Data"
    command: "python /opt/crawlers/ai_extract.py --source itviec"
    maxRetries: 2
    timeoutSeconds: 600
    dependsOn:
      - crawl-itviec

  - id: merge-and-store
    name: "Merge & Store to DB"
    command: "python /opt/crawlers/merge_store.py"
    maxRetries: 3
    timeoutSeconds: 120
    dependsOn:
      - ai-extract-linkedin
      - ai-extract-topcv
      - ai-extract-itviec
EOF

# Submit workflow
WORKFLOW_RUN_ID=$(curl -s -X POST http://localhost:8080/api/workflows/start \
  -H "Content-Type: text/plain" \
  --data-binary @job-crawler.yaml | jq -r '.workflowRunId')

echo "Workflow started: $WORKFLOW_RUN_ID"
```

**Step 2: Monitor Progress**
```bash
# Check workflow status
curl http://localhost:8080/api/workflows/$WORKFLOW_RUN_ID | jq '.'

# Watch only task statuses
watch -n 5 "curl -s http://localhost:8080/api/workflows/$WORKFLOW_RUN_ID/tasks | jq '.[] | {taskId, status, retryCount, lastError}'"
```

**Step 3: List All Running Crawlers**
```bash
curl "http://localhost:8080/api/workflows?status=RUNNING" | jq '.content[] | {id, workflowName, startedAt}'
```

### Use Case 2: Debugging Failed Workflows

**Find all failed workflows in last 24 hours**
```bash
curl "http://localhost:8080/api/workflows?status=FAILED&size=100" | \
  jq '.content[] | select(.startedAt > (now - 86400 | todate)) | {id, workflowName, startedAt}'
```

**Get detailed error information**
```bash
FAILED_RUN_ID="550e8400-e29b-41d4-a716-446655440000"

curl http://localhost:8080/api/workflows/$FAILED_RUN_ID/tasks | \
  jq '.[] | select(.status == "FAILED") | {taskId, taskName, retryCount, lastError}'
```

### Use Case 3: Worker Service Integration

**Python Worker Example**
```python
import requests
import subprocess
import json

def execute_task(workflow_run_id, task_id, command):
    """Execute a task and report result back to orchestrator"""
    
    try:
        # Execute command
        result = subprocess.run(
            command,
            shell=True,
            capture_output=True,
            text=True,
            timeout=300
        )
        
        # Report success
        if result.returncode == 0:
            requests.post(
                f"http://localhost:8080/api/workflows/task/callback",
                json={
                    "workflowRunId": workflow_run_id,
                    "taskId": task_id,
                    "success": True,
                    "lastError": None
                }
            )
            print(f"✓ Task {task_id} completed successfully")
        else:
            # Report failure
            requests.post(
                f"http://localhost:8080/api/workflows/task/callback",
                json={
                    "workflowRunId": workflow_run_id,
                    "taskId": task_id,
                    "success": False,
                    "lastError": result.stderr[:500]  # Truncate error
                }
            )
            print(f"✗ Task {task_id} failed: {result.stderr}")
            
    except subprocess.TimeoutExpired:
        # Report timeout
        requests.post(
            f"http://localhost:8080/api/workflows/task/callback",
            json={
                "workflowRunId": workflow_run_id,
                "taskId": task_id,
                "success": False,
                "lastError": "Task execution timeout (300s)"
            }
        )
        print(f"✗ Task {task_id} timed out")
    
    except Exception as e:
        # Report unexpected error
        requests.post(
            f"http://localhost:8080/api/workflows/task/callback",
            json={
                "workflowRunId": workflow_run_id,
                "taskId": task_id,
                "success": False,
                "lastError": f"Unexpected error: {str(e)}"
            }
        )
        print(f"✗ Task {task_id} error: {e}")

# Usage
execute_task(
    workflow_run_id="550e8400-e29b-41d4-a716-446655440000",
    task_id="crawl-linkedin",
    command="python /opt/crawlers/linkedin.py --max-pages 10"
)
```

---

## Roadmap

### Planned APIs (Week 1-2)

**Manual Controls:**
- `POST /api/workflows/{id}/cancel` - Cancel a running workflow
- `POST /api/workflows/{id}/tasks/{taskId}/retry` - Manually retry a failed task

**Monitoring:**
- `GET /actuator/health` - Health check endpoint
- `GET /actuator/metrics` - Prometheus metrics

### Future APIs (Week 3-4)

**Multi-Executor Support:**
- Support for `type: PYTHON`, `type: HTTP`, `type: DOCKER` in task definitions
- `POST /api/workflows/{id}/tasks/{taskId}/logs` - Stream task logs

**Scheduling:**
- `POST /api/schedules` - Create cron-based workflow schedules
- `GET /api/schedules` - List all scheduled workflows

---

## Support

For issues or questions:
- Check workflow status: `GET /api/workflows/{id}`
- Check task errors: `GET /api/workflows/{id}/tasks`
- Review logs: Check application logs in `api-service` and `worker-service`

**Current Limitations:**
- No authentication (add in Week 9+)
- No workflow cancellation (add in Week 1 Day 3)
- No manual retry (add in Week 1 Day 5)
- No real-time WebSocket updates (future enhancement)

---

**Last Updated:** November 20, 2025  
**API Version:** 1.0.0  
**Platform Version:** Development (Pre-Production)

