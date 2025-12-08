# Docker Commands - Redis & PostgreSQL Monitoring

## 🐘 PostgreSQL Database Commands

### Connect to PostgreSQL
```bash
docker exec -it job_postgres psql -U jobuser -d jobdb
```

### Quick Queries (One-liners)

#### 1. List all tables
```bash
docker exec -it job_postgres psql -U jobuser -d jobdb -c "\dt"
```

#### 2. Check workflows table schema
```bash
docker exec -it job_postgres psql -U jobuser -d jobdb -c "\d workflows"
```

#### 3. Check all workflow runs
```bash
docker exec -it job_postgres psql -U jobuser -d jobdb -c "SELECT id, status, started_at, finished_at FROM workflow_runs ORDER BY started_at DESC LIMIT 10;"
```

#### 4. Check task runs for a specific workflow
```bash
# Replace YOUR_WORKFLOW_RUN_ID with actual ID
docker exec -it job_postgres psql -U jobuser -d jobdb -c "SELECT task_id, task_name, status, retry_count, last_error FROM task_runs WHERE workflow_run_id = 'YOUR_WORKFLOW_RUN_ID' ORDER BY started_at;"
```

#### 5. Check latest workflow with all tasks
```bash
docker exec -it job_postgres psql -U jobuser -d jobdb -c "
SELECT 
    wr.id as workflow_run_id,
    wr.status as workflow_status,
    tr.task_id,
    tr.task_name,
    tr.status as task_status,
    tr.retry_count,
    tr.last_error,
    tr.started_at,
    tr.finished_at
FROM workflow_runs wr
LEFT JOIN task_runs tr ON tr.workflow_run_id = wr.id
ORDER BY wr.started_at DESC, tr.started_at ASC
LIMIT 20;
"
```

#### 6. Count workflows by status
```bash
docker exec -it job_postgres psql -U jobuser -d jobdb -c "SELECT status, COUNT(*) FROM workflow_runs GROUP BY status;"
```

#### 7. Count tasks by status
```bash
docker exec -it job_postgres psql -U jobuser -d jobdb -c "SELECT status, COUNT(*) FROM task_runs GROUP BY status;"
```

#### 8. Find failed tasks
```bash
docker exec -it job_postgres psql -U jobuser -d jobdb -c "SELECT workflow_run_id, task_id, task_name, last_error, retry_count FROM task_runs WHERE status = 'FAILED' ORDER BY finished_at DESC;"
```

#### 9. View recent workflow definitions
```bash
docker exec -it job_postgres psql -U jobuser -d jobdb -c "SELECT id, name, description, created_at FROM workflows ORDER BY created_at DESC LIMIT 5;"
```

#### 10. Get full workflow definition (YAML)
```bash
# Replace WORKFLOW_ID with actual ID
docker exec -it job_postgres psql -U jobuser -d jobdb -c "SELECT name, raw_definition FROM workflows WHERE id = 'WORKFLOW_ID';"
```

---

## 🔴 Redis Commands

### Connect to Redis CLI
```bash
docker exec -it job_redis redis-cli
```

### Quick Commands (One-liners)

#### 1. Check Redis info
```bash
docker exec -it job_redis redis-cli INFO
```

#### 2. Check all keys
```bash
docker exec -it job_redis redis-cli KEYS '*'
```

#### 3. Check stream info
```bash
docker exec -it job_redis redis-cli XINFO STREAM tasks_stream
```

#### 4. Check consumer groups
```bash
docker exec -it job_redis redis-cli XINFO GROUPS tasks_stream
```

#### 5. Check consumers in group
```bash
docker exec -it job_redis redis-cli XINFO CONSUMERS tasks_stream worker-group
```

#### 6. Check pending messages
```bash
docker exec -it job_redis redis-cli XPENDING tasks_stream worker-group
```

#### 7. Read latest messages from stream
```bash
docker exec -it job_redis redis-cli XREAD COUNT 10 STREAMS tasks_stream 0
```

#### 8. Get stream length
```bash
docker exec -it job_redis redis-cli XLEN tasks_stream
```

#### 9. Get DAG cache keys (if any)
```bash
docker exec -it job_redis redis-cli KEYS 'dag:*'
```

#### 10. Get specific DAG from cache
```bash
# Replace WORKFLOW_RUN_ID with actual ID
docker exec -it job_redis redis-cli HGETALL dag:WORKFLOW_RUN_ID
```

#### 11. Monitor real-time commands
```bash
docker exec -it job_redis redis-cli MONITOR
```

#### 12. Check memory usage
```bash
docker exec -it job_redis redis-cli INFO memory
```

---

## 🔍 Combined Monitoring Scripts

### Check Complete Workflow Status
```bash
#!/bin/bash
# Save as: check_workflow.sh
# Usage: ./check_workflow.sh <workflow_run_id>

WORKFLOW_RUN_ID=$1

echo "=== Workflow Run: $WORKFLOW_RUN_ID ==="
echo ""

echo "📊 Workflow Status:"
docker exec -it job_postgres psql -U jobuser -d jobdb -c "
SELECT id, status, started_at, finished_at 
FROM workflow_runs 
WHERE id = '$WORKFLOW_RUN_ID';
"

echo ""
echo "📋 Task Status:"
docker exec -it job_postgres psql -U jobuser -d jobdb -c "
SELECT task_id, task_name, status, retry_count, last_error, started_at, finished_at
FROM task_runs 
WHERE workflow_run_id = '$WORKFLOW_RUN_ID'
ORDER BY started_at;
"

echo ""
echo "🔴 Redis DAG Cache:"
docker exec -it job_redis redis-cli HGETALL "dag:$WORKFLOW_RUN_ID"
```

### Monitor System Health
```bash
#!/bin/bash
# Save as: monitor_system.sh

echo "=== Job Scheduler System Status ==="
echo ""

echo "📊 Workflow Statistics:"
docker exec -it job_postgres psql -U jobuser -d jobdb -c "
SELECT 
    status, 
    COUNT(*) as count,
    MIN(started_at) as first_run,
    MAX(started_at) as last_run
FROM workflow_runs 
GROUP BY status;
"

echo ""
echo "📋 Task Statistics:"
docker exec -it job_postgres psql -U jobuser -d jobdb -c "
SELECT status, COUNT(*) as count
FROM task_runs 
GROUP BY status;
"

echo ""
echo "🔴 Redis Stream Info:"
docker exec -it job_redis redis-cli XINFO STREAM tasks_stream

echo ""
echo "👥 Active Workers:"
docker exec -it job_redis redis-cli XINFO CONSUMERS tasks_stream worker-group

echo ""
echo "⏳ Pending Tasks:"
docker exec -it job_redis redis-cli XPENDING tasks_stream worker-group
```

### Watch Recent Activity
```bash
#!/bin/bash
# Save as: watch_activity.sh
# Continuously monitor recent workflows

watch -n 5 'docker exec -it job_postgres psql -U jobuser -d jobdb -c "
SELECT 
    wr.id,
    w.name,
    wr.status,
    COUNT(tr.id) as total_tasks,
    SUM(CASE WHEN tr.status = '\''SUCCESS'\'' THEN 1 ELSE 0 END) as completed,
    SUM(CASE WHEN tr.status = '\''FAILED'\'' THEN 1 ELSE 0 END) as failed,
    wr.started_at
FROM workflow_runs wr
JOIN workflows w ON w.id = wr.workflow_id
LEFT JOIN task_runs tr ON tr.workflow_run_id = wr.id
GROUP BY wr.id, w.name, wr.status, wr.started_at
ORDER BY wr.started_at DESC
LIMIT 10;
"'
```

---

## 🧹 Cleanup Commands

### Clear all workflow data
```bash
docker exec -it job_postgres psql -U jobuser -d jobdb -c "
TRUNCATE TABLE task_runs CASCADE;
TRUNCATE TABLE workflow_runs CASCADE;
TRUNCATE TABLE workflows CASCADE;
"
```

### Clear Redis stream
```bash
docker exec -it job_redis redis-cli DEL tasks_stream
docker exec -it job_redis redis-cli KEYS 'dag:*' | xargs docker exec -i job_redis redis-cli DEL
```

### Restart Docker containers
```bash
docker-compose restart postgres redis
```

---

## 🐳 Docker Container Management

### Check container status
```bash
docker-compose ps
```

### View logs
```bash
# PostgreSQL logs
docker logs job_postgres

# Redis logs
docker logs job_redis

# Follow logs in real-time
docker logs -f job_postgres
docker logs -f job_redis
```

### Container stats
```bash
docker stats job_postgres job_redis
```

### Restart containers
```bash
docker-compose restart
```

### Stop containers
```bash
docker-compose down
```

### Start containers
```bash
docker-compose up -d
```

### Full reset (delete volumes)
```bash
docker-compose down -v
docker-compose up -d
```

---

## 📝 Interactive Sessions

### PostgreSQL Interactive Session
```bash
docker exec -it job_postgres psql -U jobuser -d jobdb
```

Then use SQL commands:
```sql
-- List tables
\dt

-- Describe table
\d workflows
\d workflow_runs
\d task_runs

-- Sample queries
SELECT * FROM workflows;
SELECT * FROM workflow_runs ORDER BY started_at DESC LIMIT 5;
SELECT * FROM task_runs WHERE status = 'RUNNING';

-- Exit
\q
```

### Redis Interactive Session
```bash
docker exec -it job_redis redis-cli
```

Then use Redis commands:
```redis
# List all keys
KEYS *

# Get stream info
XINFO STREAM tasks_stream

# Get consumer group info
XINFO GROUPS tasks_stream

# Read from stream
XREAD COUNT 10 STREAMS tasks_stream 0

# Monitor commands in real-time
MONITOR

# Exit
exit
```

---

## 🎯 Quick Reference

| Task | PostgreSQL Command | Redis Command |
|------|-------------------|---------------|
| **Connect** | `docker exec -it job_postgres psql -U jobuser -d jobdb` | `docker exec -it job_redis redis-cli` |
| **List Data** | `SELECT * FROM workflows;` | `KEYS *` |
| **Check Status** | `SELECT status, COUNT(*) FROM workflow_runs GROUP BY status;` | `XINFO STREAM tasks_stream` |
| **Clear Data** | `TRUNCATE TABLE workflows CASCADE;` | `DEL tasks_stream` |
| **Exit** | `\q` | `exit` |

---

## 🚀 Pro Tips

1. **Use aliases** in your `.bashrc`:
```bash
alias pgdb='docker exec -it job_postgres psql -U jobuser -d jobdb'
alias rediscli='docker exec -it job_redis redis-cli'
alias workflow-status='docker exec -it job_postgres psql -U jobuser -d jobdb -c "SELECT id, status, started_at FROM workflow_runs ORDER BY started_at DESC LIMIT 10;"'
```

2. **Export data** to file:
```bash
docker exec -it job_postgres psql -U jobuser -d jobdb -c "SELECT * FROM workflows;" > workflows.csv
```

3. **Backup database**:
```bash
docker exec job_postgres pg_dump -U jobuser jobdb > backup.sql
```

4. **Restore database**:
```bash
cat backup.sql | docker exec -i job_postgres psql -U jobuser -d jobdb
```

---

**Last Updated:** 2025-11-12

