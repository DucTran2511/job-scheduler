# Query API Testing Guide

## 🎉 What's Been Implemented

Successfully implemented `GET /api/workflows` endpoint with full pagination and filtering support!

### ✅ New Components Created:

1. **DTOs** (Data Transfer Objects)
   - `WorkflowRunDTO` - Summary view for list endpoint
   - `WorkflowRunDetailDTO` - Detailed view with all tasks
   - `PagedResponse<T>` - Generic pagination wrapper

2. **Service Layer**
   - `WorkflowQueryService` - Business logic for querying workflows

3. **Repository Enhancements**
   - Added pagination support to `WorkflowRunRepository`
   - Added filtering by status and name

4. **REST Controller**
   - `WorkflowQueryController` - Three new endpoints

5. **Documentation**
   - Added SpringDoc OpenAPI for automatic API documentation
   - Swagger UI available at `/swagger-ui.html`

---

## 🚀 API Endpoints

### 1. List All Workflows (Paginated)
```bash
GET /api/workflows
```

**Query Parameters:**
- `page` (optional, default: 0) - Page number (0-indexed)
- `size` (optional, default: 20, max: 100) - Number of items per page
- `status` (optional) - Filter by status: `RUNNING`, `COMPLETED`, `FAILED`
- `name` (optional) - Filter by workflow name (partial match)
- `sortBy` (optional, default: startedAt) - Sort field
- `sortDirection` (optional, default: desc) - Sort direction: `asc` or `desc`

**Example Requests:**
```bash
# Get first page (20 items)
curl http://localhost:8080/api/workflows

# Get page 2 with 10 items
curl http://localhost:8080/api/workflows?page=1&size=10

# Filter by status
curl http://localhost:8080/api/workflows?status=RUNNING

# Filter by workflow name
curl http://localhost:8080/api/workflows?name=crawler

# Combined filters
curl "http://localhost:8080/api/workflows?status=COMPLETED&name=job&page=0&size=5"

# Sort by finishedAt ascending
curl "http://localhost:8080/api/workflows?sortBy=finishedAt&sortDirection=asc"
```

**Response Format:**
```json
{
  "content": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "workflowId": "workflow-123",
      "workflowName": "job_market_crawler",
      "description": "Daily job aggregation",
      "status": "COMPLETED",
      "startedAt": "2025-11-14T10:30:00",
      "finishedAt": "2025-11-14T10:35:00",
      "durationSeconds": 300,
      "totalTasks": 10,
      "completedTasks": 10,
      "failedTasks": 0,
      "runningTasks": 0,
      "pendingTasks": 0
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 45,
  "totalPages": 3,
  "first": true,
  "last": false
}
```

---

### 2. Get Workflow Details
```bash
GET /api/workflows/{id}
```

**Example:**
```bash
curl http://localhost:8080/api/workflows/550e8400-e29b-41d4-a716-446655440000
```

**Response Format:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "workflowId": "workflow-123",
  "workflowName": "job_market_crawler",
  "description": "Daily job aggregation",
  "status": "COMPLETED",
  "startedAt": "2025-11-14T10:30:00",
  "finishedAt": "2025-11-14T10:35:00",
  "durationSeconds": 300,
  "tasks": [
    {
      "id": "task-run-1",
      "taskId": "crawl-linkedin",
      "taskName": "Crawl LinkedIn",
      "status": "SUCCESS",
      "retryCount": 0,
      "maxRetries": 3,
      "lastError": null,
      "startedAt": "2025-11-14T10:30:05",
      "finishedAt": "2025-11-14T10:32:00"
    },
    {
      "id": "task-run-2",
      "taskId": "crawl-topcv",
      "taskName": "Crawl TopCV",
      "status": "SUCCESS",
      "retryCount": 1,
      "maxRetries": 3,
      "lastError": "Connection timeout",
      "startedAt": "2025-11-14T10:30:05",
      "finishedAt": "2025-11-14T10:32:10"
    }
  ]
}
```

---

### 3. Get Workflow Tasks
```bash
GET /api/workflows/{id}/tasks
```

**Example:**
```bash
curl http://localhost:8080/api/workflows/550e8400-e29b-41d4-a716-446655440000/tasks
```

**Response:** Array of tasks (same format as in detailed view)

---

## 🧪 Testing the Implementation

### Step 1: Build the Project
```bash
cd /mnt/data/projects/java/job-scheduler
mvn clean install -DskipTests
```

### Step 2: Start Infrastructure
```bash
docker-compose up -d postgres redis
```

### Step 3: Start API Service
```bash
cd api-service
mvn spring-boot:run
```

### Step 4: Submit Test Workflows
```bash
# Submit a test workflow
curl -X POST http://localhost:8080/api/workflows/start \
  -H "Content-Type: text/plain" \
  --data-binary @src/main/resources/workflow-samples/daily_etl.yaml

# Submit another one
curl -X POST http://localhost:8080/api/workflows/start \
  -H "Content-Type: text/plain" \
  --data-binary @src/main/resources/workflow-samples/web_deployment.yaml
```

### Step 5: Query Workflows
```bash
# List all workflows
curl http://localhost:8080/api/workflows | jq .

# Get specific workflow (replace ID with actual from submission)
curl http://localhost:8080/api/workflows/{workflow-run-id} | jq .

# Filter running workflows
curl http://localhost:8080/api/workflows?status=RUNNING | jq .
```

---

## 📊 Swagger UI (Interactive Documentation)

Once the application is running, visit:
```
http://localhost:8080/swagger-ui.html
```

This provides:
- Interactive API documentation
- Try-it-out functionality
- Request/response examples
- Schema definitions

---

## 🔍 Filter Examples

### By Status
```bash
# Only running workflows
curl "http://localhost:8080/api/workflows?status=RUNNING"

# Only completed workflows
curl "http://localhost:8080/api/workflows?status=COMPLETED"

# Only failed workflows
curl "http://localhost:8080/api/workflows?status=FAILED"
```

### By Name (Partial Match)
```bash
# Workflows with "crawler" in name
curl "http://localhost:8080/api/workflows?name=crawler"

# Workflows with "etl" in name
curl "http://localhost:8080/api/workflows?name=etl"
```

### Combined Filters
```bash
# Running crawlers, page 1, 10 items
curl "http://localhost:8080/api/workflows?status=RUNNING&name=crawler&page=0&size=10"

# Completed ETL jobs, sorted by finish time
curl "http://localhost:8080/api/workflows?status=COMPLETED&name=etl&sortBy=finishedAt&sortDirection=desc"
```

---

## 📈 Pagination Examples

```bash
# First page (items 0-19)
curl "http://localhost:8080/api/workflows?page=0&size=20"

# Second page (items 20-39)
curl "http://localhost:8080/api/workflows?page=1&size=20"

# Small pages (5 items)
curl "http://localhost:8080/api/workflows?size=5"

# Large pages (max 100)
curl "http://localhost:8080/api/workflows?size=100"
```

---

## 🐛 Troubleshooting

### Issue: Empty response
**Cause:** No workflows submitted yet
**Solution:** Submit a workflow first using `/api/workflows/start`

### Issue: 404 Not Found
**Cause:** Invalid workflow run ID
**Solution:** Get valid ID from list endpoint first

### Issue: 400 Bad Request
**Cause:** Invalid status value
**Solution:** Use only: RUNNING, COMPLETED, FAILED (case-insensitive)

### Issue: Database connection error
**Cause:** PostgreSQL not running
**Solution:** `docker-compose up -d postgres`

---

## 🎯 Next Steps

### Week 1 Remaining Tasks:
- ✅ Query APIs (DONE!)
- [ ] Error handling & retry logic
- [ ] Cancel workflow endpoint
- [ ] Workflow timeout mechanism

### Test with Your Crawler:
```bash
# 1. Submit job crawler workflow
curl -X POST http://localhost:8080/api/workflows/start \
  -H "Content-Type: text/plain" \
  --data-binary @workflow-samples/job_market_crawler.yaml

# 2. Monitor progress
watch -n 2 'curl -s http://localhost:8080/api/workflows?status=RUNNING | jq .'

# 3. Check completion
curl http://localhost:8080/api/workflows/{id} | jq .
```

---

## 📝 Response Fields Explained

### WorkflowRunDTO Fields:
- `id` - Unique workflow run identifier
- `workflowId` - Template workflow ID
- `workflowName` - Human-readable name
- `status` - Current state (RUNNING/COMPLETED/FAILED)
- `startedAt` - When execution began
- `finishedAt` - When execution ended (null if running)
- `durationSeconds` - Total execution time in seconds
- `totalTasks` - Number of tasks in workflow
- `completedTasks` - Tasks that succeeded
- `failedTasks` - Tasks that failed permanently
- `runningTasks` - Tasks currently executing
- `pendingTasks` - Tasks waiting for dependencies

### Pagination Fields:
- `content` - Array of results for current page
- `page` - Current page number (0-indexed)
- `size` - Number of items per page
- `totalElements` - Total number of workflows matching filters
- `totalPages` - Total number of pages
- `first` - Is this the first page?
- `last` - Is this the last page?

---

## 🚀 Performance Notes

- **Max page size:** 100 items (prevents memory issues)
- **Default page size:** 20 items
- **Sorting:** Optimized with database indexes
- **Filtering:** Server-side (efficient for large datasets)
- **Task summaries:** Calculated in single query per workflow

---

## 🔗 Related Endpoints

Already existing:
- `POST /api/workflows/start` - Submit new workflow
- `POST /api/workflows/{runId}/tasks/{taskId}/complete` - Task completion callback

Coming soon (Week 1):
- `POST /api/workflows/{id}/cancel` - Cancel running workflow
- `POST /api/workflows/{id}/tasks/{taskId}/retry` - Retry failed task
- `GET /api/workflows/{id}/logs` - Get execution logs

