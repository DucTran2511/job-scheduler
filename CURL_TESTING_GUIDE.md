# Full Flow CURL Testing Guide

## Quick Start - Complete Flow Testing

### Automated Script
```bash
./test-full-workflow.sh
```

---

## Manual Testing - Step by Step

### 1️⃣ Start Workflow
```bash
curl -X POST "http://localhost:8080/api/workflows/start" \
  -H "Content-Type: text/plain" \
  --data-binary "@api-service/src/main/resources/workflow-samples/daily_backup.yaml"
```

**Expected Response:**
```json
{
  "workflowRunId": "wf_run_1234567890"
}
```

**Save the workflowRunId for next steps!**

---

### 2️⃣ Get Workflow Status
```bash
# Replace {workflowRunId} with the ID from step 1
curl -X GET "http://localhost:8080/api/workflows/{workflowRunId}"
```

**Expected Response:**
```json
{
  "id": "wf_run_1234567890",
  "workflowName": "database_backup_workflow",
  "status": "RUNNING",
  "startedAt": "2025-12-01T10:30:00",
  "completedAt": null,
  "totalTasks": 6,
  "completedTasks": 0,
  "failedTasks": 0
}
```

---

### 3️⃣ Get Task Details
```bash
curl -X GET "http://localhost:8080/api/workflows/{workflowRunId}/tasks"
```

**Expected Response:**
```json
[
  {
    "id": "backup_postgres",
    "name": "Backup PostgreSQL Database",
    "status": "PENDING",
    "startedAt": null,
    "completedAt": null,
    "retries": 0,
    "maxRetries": 2
  },
  {
    "id": "compress_backup",
    "name": "Compress Backup File",
    "status": "WAITING",
    "dependsOn": ["backup_postgres"],
    "startedAt": null,
    "completedAt": null
  }
]
```

---

### 4️⃣ Complete Tasks (Manual - for testing without worker)

#### Method A: URL Parameters
```bash
# Success
curl -X POST "http://localhost:8080/api/workflows/{workflowRunId}/tasks/backup_postgres/complete?success=true"

# Failure with error message
curl -X POST "http://localhost:8080/api/workflows/{workflowRunId}/tasks/backup_postgres/complete?success=false" \
  -H "Content-Type: text/plain" \
  -d "Database connection timeout"
```

#### Method B: JSON Body
```bash
# Success
curl -X POST "http://localhost:8080/api/workflows/task/callback" \
  -H "Content-Type: application/json" \
  -d '{
    "workflowRunId": "{workflowRunId}",
    "taskId": "backup_postgres",
    "success": true,
    "lastError": null
  }'

# Failure
curl -X POST "http://localhost:8080/api/workflows/task/callback" \
  -H "Content-Type: application/json" \
  -d '{
    "workflowRunId": "{workflowRunId}",
    "taskId": "backup_postgres",
    "success": false,
    "lastError": "Command execution failed"
  }'
```

---

## 🔄 Complete Workflow Simulation

### Example: Complete all tasks in order

```bash
# Save your workflow run ID
WORKFLOW_ID="wf_run_1234567890"

# Task 1: backup_postgres
curl -X POST "http://localhost:8080/api/workflows/task/callback" \
  -H "Content-Type: application/json" \
  -d "{\"workflowRunId\":\"${WORKFLOW_ID}\",\"taskId\":\"backup_postgres\",\"success\":true,\"lastError\":null}"

# Check status
curl -X GET "http://localhost:8080/api/workflows/${WORKFLOW_ID}"

# Task 2: compress_backup (depends on task 1)
curl -X POST "http://localhost:8080/api/workflows/task/callback" \
  -H "Content-Type: application/json" \
  -d "{\"workflowRunId\":\"${WORKFLOW_ID}\",\"taskId\":\"compress_backup\",\"success\":true,\"lastError\":null}"

# Task 3: upload_to_s3
curl -X POST "http://localhost:8080/api/workflows/task/callback" \
  -H "Content-Type: application/json" \
  -d "{\"workflowRunId\":\"${WORKFLOW_ID}\",\"taskId\":\"upload_to_s3\",\"success\":true,\"lastError\":null}"

# Task 4: verify_backup
curl -X POST "http://localhost:8080/api/workflows/task/callback" \
  -H "Content-Type: application/json" \
  -d "{\"workflowRunId\":\"${WORKFLOW_ID}\",\"taskId\":\"verify_backup\",\"success\":true,\"lastError\":null}"

# Task 5: cleanup_old_backups
curl -X POST "http://localhost:8080/api/workflows/task/callback" \
  -H "Content-Type: application/json" \
  -d "{\"workflowRunId\":\"${WORKFLOW_ID}\",\"taskId\":\"cleanup_old_backups\",\"success\":true,\"lastError\":null}"

# Task 6: send_report
curl -X POST "http://localhost:8080/api/workflows/task/callback" \
  -H "Content-Type: application/json" \
  -d "{\"workflowRunId\":\"${WORKFLOW_ID}\",\"taskId\":\"send_report\",\"success\":true,\"lastError\":null}"

# Final status
curl -X GET "http://localhost:8080/api/workflows/${WORKFLOW_ID}"
```

---

## 🧪 Testing Scenarios

### Scenario 1: Successful Workflow
```bash
# 1. Submit workflow
RESPONSE=$(curl -s -X POST "http://localhost:8080/api/workflows/start" \
  -H "Content-Type: text/plain" \
  --data-binary "@api-service/src/main/resources/workflow-samples/daily_backup.yaml")

# 2. Extract workflow ID
WORKFLOW_ID=$(echo $RESPONSE | grep -o '"workflowRunId":"[^"]*"' | cut -d'"' -f4)

# 3. Complete all tasks successfully
for task in backup_postgres compress_backup upload_to_s3 verify_backup cleanup_old_backups send_report; do
  curl -X POST "http://localhost:8080/api/workflows/task/callback" \
    -H "Content-Type: application/json" \
    -d "{\"workflowRunId\":\"${WORKFLOW_ID}\",\"taskId\":\"${task}\",\"success\":true,\"lastError\":null}"
  sleep 1
done

# 4. Check final status
curl -X GET "http://localhost:8080/api/workflows/${WORKFLOW_ID}" | jq '.'
```

### Scenario 2: Failed Task with Retry
```bash
# Complete task with failure
curl -X POST "http://localhost:8080/api/workflows/${WORKFLOW_ID}/tasks/backup_postgres/complete?success=false" \
  -H "Content-Type: text/plain" \
  -d "Connection timeout"

# Check if task is retried
curl -X GET "http://localhost:8080/api/workflows/${WORKFLOW_ID}/tasks" | jq '.[] | select(.id=="backup_postgres")'
```

### Scenario 3: Workflow with Dependencies
```bash
# Try to complete dependent task before parent (should not work)
curl -X POST "http://localhost:8080/api/workflows/task/callback" \
  -H "Content-Type: application/json" \
  -d "{\"workflowRunId\":\"${WORKFLOW_ID}\",\"taskId\":\"compress_backup\",\"success\":true,\"lastError\":null}"

# First complete the parent task
curl -X POST "http://localhost:8080/api/workflows/task/callback" \
  -H "Content-Type: application/json" \
  -d "{\"workflowRunId\":\"${WORKFLOW_ID}\",\"taskId\":\"backup_postgres\",\"success\":true,\"lastError\":null}"

# Now the dependent task should be available
curl -X GET "http://localhost:8080/api/workflows/${WORKFLOW_ID}/tasks" | jq '.'
```

---

## 🏥 Health Check
```bash
# Basic health
curl http://localhost:8080/actuator/health

# Detailed health
curl http://localhost:8080/actuator/health | jq '.'
```

---

## 📊 Pretty Print with jq

Install jq for better JSON formatting:
```bash
sudo apt install jq
```

Then use:
```bash
curl -s http://localhost:8080/api/workflows/${WORKFLOW_ID} | jq '.'
```

---

## 🐛 Debugging Tips

### Check if API is running:
```bash
curl http://localhost:8080/actuator/health
```

### Monitor logs in real-time:
```bash
# In api-service directory
tail -f logs/application.log
```

### Test with verbose output:
```bash
curl -v -X POST "http://localhost:8080/api/workflows/start" \
  -H "Content-Type: text/plain" \
  --data-binary "@api-service/src/main/resources/workflow-samples/daily_backup.yaml"
```

---

## 📝 Notes

- **Worker Service**: If the worker service is running, tasks will be picked up automatically from Redis streams
- **Manual Testing**: Use the task completion endpoints to simulate worker behavior when testing without the worker
- **Dependencies**: Tasks with `depends_on` will only run after their parent tasks complete successfully
- **Retries**: Failed tasks will automatically retry based on `max_retries` configuration

