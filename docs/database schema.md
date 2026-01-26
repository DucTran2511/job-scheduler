# Database Relational Diagram

## Current Database Schema

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                              JOB SCHEDULER DATABASE DIAGRAM                                  │
└─────────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────┐
│         workflows           │
├─────────────────────────────┤
│ PK  id          UUID        │◄─────────────────────────────────┐
│     name        VARCHAR     │◄──────────────────┐              │
│     description VARCHAR     │                   │              │
│     raw_definition TEXT     │                   │              │
│     created_at  TIMESTAMP   │                   │              │
└─────────────────────────────┘                   │              │
          │                                       │              │
          │ 1:N                                   │ 1:N          │ 1:N
          │                                       │              │
          ▼                                       │              │
┌─────────────────────────────┐                   │    ┌─────────┴─────────────   ┐
│      workflow_schedules     │                   │    │       workflow_runs      │
│        (PLANNED)            │                   │    ├───────────────────────   ┤
├─────────────────────────────┤                   │    │ PK  id         UUID      │
│ PK  id          UUID        │                   │    │ FK  workflow_id UUID     │
│ FK  workflow_id UUID        │───────────────────┘    │     status     ENUM      │
│     name        VARCHAR     │                        │     started_at TIMESTAMP │
│     cron_expression VARCHAR │    triggers            │     finished_at TIMESTAMP│
│     schedule_type ENUM      │────────────────────────└───────────┬───────────   ┘
│     status      ENUM        │                                    │
│     next_run_at TIMESTAMP   │                                    │
│ FK  last_run_id UUID        │────────────────────────────────────│───┐
└─────────────────────────────┘                                    │   │
          │                                                        │   │
          │ 1:N                                               1:N  │   │
          │                                                        │   │
          ▼                                                        ▼   │
┌─────────────────────────────┐                    ┌───────────────────┴───┐
│    schedule_executions      │                    │        task_runs      │
│         (PLANNED)           │                    ├───────────────────────┤
├─────────────────────────────┤                    │ PK  id           UUID │
│ PK  id          UUID        │                    │ FK  workflow_run_id   │
│ FK  schedule_id UUID        │                    │     task_id    VARCHAR│
│ FK  workflow_run_id UUID    │───────────────────▶│     task_name  VARCHAR│
│     scheduled_time TIMESTAMP│                    │     command    VARCHAR│
│     actual_time TIMESTAMP   │                    │     task_type  VARCHAR│
│     trigger_type ENUM       │                    │     status     ENUM   │
│     status      ENUM        │                    │     retry_count INT   │
│     max_retries INT         │                    │     last_error TEXT   │
└─────────────────────────────┘                    └───────────────────────┘
```

---

## Complete Relationship Table (All Tables)

```
┌────────────────────────────────────────────────────────────────────────────────────────────────┐
│                              ALL TABLE RELATIONSHIPS                                            │
├────────────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                                │
│  ┌─────────────────────────────────────────────────────────────────────────────────────────┐  │
│  │                                    workflows                                             │  │
│  │                                   (Master Table)                                         │  │
│  └──────────────────────────────┬─────────────────────┬────────────────────────────────────┘  │
│                                 │                     │                                        │
│                    ┌────────────┘                     └────────────┐                          │
│                    │ 1:N                                      1:N  │                          │
│                    ▼                                               ▼                          │
│  ┌─────────────────────────────────────┐     ┌─────────────────────────────────────┐         │
│  │        workflow_schedules           │     │          workflow_runs              │         │
│  │            (PLANNED)                │     │          (Implemented)              │         │
│  └─────────────────┬───────────────────┘     └─────────────────┬───────────────────┘         │
│                    │                                           │                              │
│       ┌────────────┤                              ┌────────────┤                              │
│       │ 1:N        │ 1:1 (last_run)               │ 1:N        │ 1:1 (triggered by)          │
│       ▼            │                              ▼            │                              │
│  ┌────────────────┐│                         ┌────────────────┐│                              │
│  │  schedule_     ││                         │   task_runs    ││                              │
│  │  executions    │└────────────────────────▶│  (Implemented) │◄─────────────────────────────┤
│  │   (PLANNED)    │        references        └────────────────┘   (schedule_execution        │
│  └────────────────┴──────────────────────────────────────────────  links to workflow_run)    │
│                                                                                                │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Detailed Relationship Matrix

| From Table | To Table | Relationship | FK Column | Description |
|------------|----------|--------------|-----------|-------------|
| **workflows** | workflow_runs | 1:N | workflow_runs.workflow_id | One workflow can be executed many times |
| **workflows** | workflow_schedules | 1:N | workflow_schedules.workflow_id | One workflow can have many schedules |
| **workflow_runs** | task_runs | 1:N | task_runs.workflow_run_id | One run contains many task executions |
| **workflow_schedules** | schedule_executions | 1:N | schedule_executions.schedule_id | One schedule triggers many executions |
| **workflow_schedules** | workflow_runs | 1:1 | workflow_schedules.last_run_id | Schedule references its last run |
| **schedule_executions** | workflow_runs | N:1 | schedule_executions.workflow_run_id | Execution links to the triggered run |

---

## Relationship Cardinality Diagram

```
                                    ┌─────────────────┐
                                    │    workflows    │
                                    │   (1 record)    │
                                    └────────┬────────┘
                                             │
                    ┌────────────────────────┼────────────────────────┐
                    │                        │                        │
                    ▼ (0..N)                 │                        ▼ (0..N)
        ┌───────────────────────┐            │            ┌───────────────────────┐
        │  workflow_schedules   │            │            │    workflow_runs      │
        │   (many schedules)    │            │            │    (many runs)        │
        └───────────┬───────────┘            │            └───────────┬───────────┘
                    │                        │                        │
                    │ (0..N)                 │                        │ (1..N)
                    │                        │                        │
                    ▼                        │                        ▼
        ┌───────────────────────┐            │            ┌───────────────────────┐
        │ schedule_executions   │────────────┼───────────▶│      task_runs        │
        │  (execution history)  │            │            │   (task instances)    │
        └───────────────────────┘            │            └───────────────────────┘
                    │                        │                        ▲
                    │         references     │                        │
                    └────────────────────────┴────────────────────────┘
                                   (via workflow_run_id)
```

---

## Foreign Key Constraints

```sql
-- CURRENT (Implemented)
ALTER TABLE workflow_runs 
  ADD CONSTRAINT fk_workflow_runs_workflow 
  FOREIGN KEY (workflow_id) REFERENCES workflows(id);

ALTER TABLE task_runs 
  ADD CONSTRAINT fk_task_runs_workflow_run 
  FOREIGN KEY (workflow_run_id) REFERENCES workflow_runs(id);

-- PLANNED (For Scheduled Workflows)
ALTER TABLE workflow_schedules 
  ADD CONSTRAINT fk_schedules_workflow 
  FOREIGN KEY (workflow_id) REFERENCES workflows(id);

ALTER TABLE workflow_schedules 
  ADD CONSTRAINT fk_schedules_last_run 
  FOREIGN KEY (last_run_id) REFERENCES workflow_runs(id);

ALTER TABLE schedule_executions 
  ADD CONSTRAINT fk_executions_schedule 
  FOREIGN KEY (schedule_id) REFERENCES workflow_schedules(id);

ALTER TABLE schedule_executions 
  ADD CONSTRAINT fk_executions_workflow_run 
  FOREIGN KEY (workflow_run_id) REFERENCES workflow_runs(id);
```

---

## Entity Relationship Summary

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                         RELATIONSHIP CARDINALITY                              │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  workflows ─────────────< workflow_runs >─────────────< task_runs            │
│     (1)         has many      (*)           has many      (*)                │
│                                                                              │
│  workflows ─────────────< workflow_schedules >────────< schedule_executions  │
│     (1)         has many      (*)              has many      (*)             │
│                                                                              │
│  workflow_runs ─────────< schedule_executions                                │
│       (1)        referenced by   (*)                                         │
│                                                                              │
│  workflow_schedules ────────> workflow_runs (last_run_id)                    │
│       (*)            references    (1)                                       │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## Redis Data Structures

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                              REDIS DATA STRUCTURES DIAGRAM                                   │
└─────────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                    REDIS STREAMS                                             │   │
│                                                                                             │   │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐   │
│  │  Stream: tasks_stream                                                                │   │
│  ├─────────────────────────────────────────────────────────────────────────────────────┤   │
│  │                                                                                     │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐            │   │
│  │  │ Message 1    │  │ Message 2    │  │ Message 3    │  │ Message N    │            │   │
│  │  │ ID: 1234-0   │  │ ID: 1234-1   │  │ ID: 1235-0   │  │ ID: XXXX-X   │            │   │
│  │  ├──────────────┤  ├──────────────┤  ├──────────────┤  ├──────────────┤            │   │
│  │  │workflowRunId │  │workflowRunId │  │workflowRunId │  │workflowRunId │            │   │
│  │  │taskId        │  │taskId        │  │taskId        │  │taskId        │   ...      │   │
│  │  │taskType      │  │taskType      │  │taskType      │  │taskType      │            │   │
│  │  │command       │  │command       │  │command       │  │command       │            │   │
│  │  │taskName      │  │taskName      │  │taskName      │  │taskName      │            │   │
│  │  │timeout       │  │timeout       │  │timeout       │  │timeout       │            │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘            │   │
│  │                                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                  CONSUMER GROUPS                                             │   │
│                                                                                             │   │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐   │
│  │  Consumer Group: worker-group                                                        │   │
│  ├─────────────────────────────────────────────────────────────────────────────────────┤   │
│  │                                                                                     │   │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐                      │   │
│  │  │   Consumer 1    │  │   Consumer 2    │  │   Consumer N    │                      │   │
│  │  │   (worker-1)    │  │   (worker-2)    │  │   (worker-N)    │                      │   │
│  │  ├─────────────────┤  ├─────────────────┤  ├─────────────────┤                      │   │
│  │  │ Pending: [msg1] │  │ Pending: [msg2] │  │ Pending: [msgN] │                      │   │
│  │  │ Last ID: 1234-0 │  │ Last ID: 1234-1 │  │ Last ID: XXXX-X │                      │   │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────┘                      │   │
│  │                                                                                     │   │
│  │  Features:                                                                          │   │
│  │  • Each message delivered to ONE consumer only                                      │   │
│  │  • Automatic load balancing across consumers                                        │   │
│  │  • Message acknowledgment (XACK) after processing                                   │   │
│  │  • Pending entries list (PEL) for retry handling                                    │   │
│  │                                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                    DAG CACHE (HASH)                                          │   │
│                                                                                             │   │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐   │
│  │  Key: dag:{workflowRunId}                                                            │   │
│  │  Type: HASH                                                                          │   │
│  ├─────────────────────────────────────────────────────────────────────────────────────┤   │
│  │                                                                                     │   │
│  │  Example: dag:abc-123-def-456                                                       │   │
│  │  ┌────────────────────────────────────────────────────────────────────────────┐    │   │
│  │  │  Field (taskId)    │  Value (dependencies as JSON array)                   │    │   │
│  │  ├────────────────────┼───────────────────────────────────────────────────────┤    │   │
│  │  │  task_1            │  []                          (no dependencies)        │    │   │
│  │  │  task_2            │  []                          (no dependencies)        │    │   │
│  │  │  task_3            │  ["task_1", "task_2"]        (depends on 1 & 2)       │    │   │
│  │  │  task_4            │  ["task_3"]                  (depends on 3)           │    │   │
│  │  │  task_5            │  ["task_3", "task_4"]        (depends on 3 & 4)       │    │   │
│  │  └────────────────────┴───────────────────────────────────────────────────────┘    │   │
│  │                                                                                     │   │
│  │  Purpose:                                                                           │   │
│  │  • Store workflow DAG structure for stateless orchestration                         │   │
│  │  • Fast lookup of task dependencies                                                 │   │
│  │  • Auto-deleted when workflow completes/fails                                       │   │
│  │                                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Redis Data Flow

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                              REDIS DATA FLOW DIAGRAM                                         │
└─────────────────────────────────────────────────────────────────────────────────────────────┘

                                    ┌───────────────────┐
                                    │   API Service     │
                                    │  (Orchestrator)   │
                                    └─────────┬─────────┘
                                              │
                    ┌─────────────────────────┼─────────────────────────┐
                    │                         │                         │
                    ▼                         ▼                         │
         ┌──────────────────┐      ┌──────────────────┐                │
         │   XADD task to   │      │   HSET DAG to    │                │
         │   tasks_stream   │      │  dag:{runId}     │                │
         └────────┬─────────┘      └──────────────────┘                │
                  │                                                     │
                  ▼                                                     │
         ┌──────────────────────────────────────────────┐              │
         │              REDIS SERVER                     │              │
         │  ┌─────────────────┐  ┌─────────────────┐    │              │
         │  │  tasks_stream   │  │  dag:{runId}    │    │              │
         │  │  (Stream)       │  │  (Hash)         │    │              │
         │  └────────┬────────┘  └─────────────────┘    │              │
         └───────────┼──────────────────────────────────┘              │
                     │                                                  │
                     │ XREADGROUP                                       │
                     ▼                                                  │
         ┌──────────────────────────────────────────────┐              │
         │            Worker Service(s)                  │              │
         │  ┌─────────────┐ ┌─────────────┐             │              │
         │  │  Consumer 1 │ │  Consumer 2 │  ...        │              │
         │  └──────┬──────┘ └──────┬──────┘             │              │
         └─────────┼───────────────┼────────────────────┘              │
                   │               │                                    │
                   │  Execute      │  Execute                          │
                   ▼               ▼                                    │
         ┌─────────────────────────────────────────────┐               │
         │           Task Execution                     │               │
         │  • Shell commands                            │               │
         │  • HTTP requests (planned)                   │               │
         │  • Python scripts (planned)                  │               │
         │  • Docker containers (planned)               │               │
         └─────────────────┬───────────────────────────┘               │
                           │                                            │
                           │ Callback (success/failure)                 │
                           └────────────────────────────────────────────┘
```

---

## Redis Keys Reference

| Key Pattern | Type | TTL | Description |
|-------------|------|-----|-------------|
| `tasks_stream` | Stream | Permanent | Main task queue for workers |
| `dag:{workflowRunId}` | Hash | Until workflow completes | DAG structure cache |
| `worker-group` | Consumer Group | N/A | Group for load balancing |

---

## Redis Commands Used

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                         REDIS COMMANDS IN USE                                 │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  STREAM OPERATIONS:                                                          │
│  ├─ XADD tasks_stream * field1 value1 ...    (Publish task)                 │
│  ├─ XGROUP CREATE tasks_stream worker-group   (Create consumer group)       │
│  ├─ XREADGROUP GROUP worker-group consumer    (Read messages)               │
│  └─ XACK tasks_stream worker-group messageId  (Acknowledge message)         │
│                                                                              │
│  HASH OPERATIONS (DAG Cache):                                                │
│  ├─ HSET dag:{runId} taskId dependencies      (Store DAG)                   │
│  ├─ HGETALL dag:{runId}                       (Load entire DAG)             │
│  └─ DEL dag:{runId}                           (Delete DAG after completion) │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## PostgreSQL + Redis Integration

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                         COMPLETE DATA ARCHITECTURE                                           │
└─────────────────────────────────────────────────────────────────────────────────────────────┘

     ┌─────────────────────────────────────────────────────────────────────────────────────┐
     │                              PERSISTENT STORAGE (PostgreSQL)                         │
     │                                                                                     │
     │   ┌────────────┐      ┌────────────────┐      ┌─────────────┐                      │
     │   │ workflows  │──1:N─│ workflow_runs  │──1:N─│  task_runs  │                      │
     │   │            │      │                │      │             │                      │
     │   │ Definition │      │ Run metadata   │      │ Task state  │                      │
     │   │ Storage    │      │ Status tracking│      │ Retry info  │                      │
     │   └────────────┘      └────────────────┘      └─────────────┘                      │
     │                                                                                     │
     │   Purpose: Source of truth, durability, historical data, queries                   │
     └─────────────────────────────────────────────────────────────────────────────────────┘

                                          │
                                          │ Sync
                                          ▼

     ┌─────────────────────────────────────────────────────────────────────────────────────┐
     │                              IN-MEMORY QUEUE (Redis)                                 │
     │                                                                                     │
     │   ┌─────────────────────────────┐      ┌─────────────────────────────┐             │
     │   │      tasks_stream           │      │      dag:{runId}            │             │
     │   │      (Redis Stream)         │      │      (Redis Hash)           │             │
     │   │                             │      │                             │             │
     │   │  • Task dispatch queue      │      │  • DAG structure cache      │             │
     │   │  • Consumer group support   │      │  • Dependency lookup        │             │
     │   │  • At-least-once delivery   │      │  • Stateless orchestration  │             │
     │   └─────────────────────────────┘      └─────────────────────────────┘             │
     │                                                                                     │
     │   Purpose: Fast message passing, real-time task distribution, ephemeral state      │
     └─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Planned Tables - Examples

### workflow_schedules Table (PLANNED)

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                              workflow_schedules - SAMPLE DATA                                │
└─────────────────────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ id (PK)                              │ workflow_id (FK)                      │ name                    │ cron_expression  │ schedule_type │
├──────────────────────────────────────┼───────────────────────────────────────┼─────────────────────────┼──────────────────┼───────────────┤
│ sch-001-uuid                         │ wf-daily-backup-uuid                  │ Daily Backup 2AM        │ 0 2 * * *        │ CRON          │
│ sch-002-uuid                         │ wf-data-sync-uuid                     │ Hourly Data Sync        │ 0 * * * *        │ CRON          │
│ sch-003-uuid                         │ wf-report-gen-uuid                    │ Monthly Report          │ 0 0 1 * *        │ CRON          │
│ sch-004-uuid                         │ wf-cleanup-uuid                       │ One-time Cleanup        │ NULL             │ ONE_TIME      │
│ sch-005-uuid                         │ wf-health-check-uuid                  │ Health Check 5min       │ */5 * * * *      │ CRON          │
└──────────────────────────────────────┴───────────────────────────────────────┴─────────────────────────┴──────────────────┴───────────────┘

┌──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ (continued)  │ status   │ timezone        │ next_run_at              │ last_run_id (FK)               │ created_at               │
├──────────────┼──────────┼─────────────────┼──────────────────────────┼────────────────────────────────┼──────────────────────────┤
│              │ ACTIVE   │ Asia/Ho_Chi_Minh│ 2025-12-13 02:00:00      │ run-abc-123-uuid               │ 2025-01-15 10:30:00      │
│              │ ACTIVE   │ UTC             │ 2025-12-12 15:00:00      │ run-def-456-uuid               │ 2025-02-20 08:00:00      │
│              │ PAUSED   │ America/New_York│ 2026-01-01 00:00:00      │ run-ghi-789-uuid               │ 2025-03-01 12:00:00      │
│              │ COMPLETED│ UTC             │ NULL                     │ run-jkl-012-uuid               │ 2025-12-01 09:00:00      │
│              │ ACTIVE   │ UTC             │ 2025-12-12 14:35:00      │ run-mno-345-uuid               │ 2025-06-10 14:00:00      │
└──────────────┴──────────┴─────────────────┴──────────────────────────┴────────────────────────────────┴──────────────────────────┘
```

**Schedule Types:**
| Type | Description | Example |
|------|-------------|---------|
| `CRON` | Recurring schedule using cron expression | `0 2 * * *` (daily at 2 AM) |
| `ONE_TIME` | Single execution at specific time | Run once on 2025-12-15 10:00 |
| `INTERVAL` | Fixed interval between runs | Every 30 minutes |

**Status Values:**
| Status | Description |
|--------|-------------|
| `ACTIVE` | Schedule is running, will trigger at next_run_at |
| `PAUSED` | Temporarily stopped, won't trigger until resumed |
| `DISABLED` | Permanently disabled |
| `COMPLETED` | One-time schedule that has finished |

---

### schedule_executions Table (PLANNED)

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                            schedule_executions - SAMPLE DATA                                 │
└─────────────────────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ id (PK)                │ schedule_id (FK)       │ workflow_run_id (FK)   │ scheduled_time           │ actual_time              │ trigger_type  │
├────────────────────────┼────────────────────────┼────────────────────────┼──────────────────────────┼──────────────────────────┼───────────────┤
│ exec-001-uuid          │ sch-001-uuid           │ run-abc-123-uuid       │ 2025-12-12 02:00:00      │ 2025-12-12 02:00:03      │ SCHEDULED     │
│ exec-002-uuid          │ sch-001-uuid           │ run-xyz-789-uuid       │ 2025-12-11 02:00:00      │ 2025-12-11 02:00:01      │ SCHEDULED     │
│ exec-003-uuid          │ sch-002-uuid           │ run-def-456-uuid       │ 2025-12-12 14:00:00      │ 2025-12-12 14:00:05      │ SCHEDULED     │
│ exec-004-uuid          │ sch-001-uuid           │ run-manual-001-uuid    │ NULL                     │ 2025-12-12 10:30:00      │ MANUAL        │
│ exec-005-uuid          │ sch-003-uuid           │ run-catch-001-uuid     │ 2025-11-01 00:00:00      │ 2025-12-01 08:00:00      │ CATCH_UP      │
│ exec-006-uuid          │ sch-005-uuid           │ run-mno-345-uuid       │ 2025-12-12 14:30:00      │ 2025-12-12 14:30:02      │ SCHEDULED     │
└────────────────────────┴────────────────────────┴────────────────────────┴──────────────────────────┴──────────────────────────┴───────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ (continued)  │ status      │ error_message                              │ created_at               │
├──────────────┼─────────────┼────────────────────────────────────────────┼──────────────────────────┤
│              │ COMPLETED   │ NULL                                       │ 2025-12-12 02:00:03      │
│              │ COMPLETED   │ NULL                                       │ 2025-12-11 02:00:01      │
│              │ RUNNING     │ NULL                                       │ 2025-12-12 14:00:05      │
│              │ COMPLETED   │ NULL                                       │ 2025-12-12 10:30:00      │
│              │ FAILED      │ Catch-up execution failed: DB timeout      │ 2025-12-01 08:00:00      │
│              │ COMPLETED   │ NULL                                       │ 2025-12-12 14:30:02      │
└──────────────┴─────────────┴────────────────────────────────────────────┴──────────────────────────┘
```

**Trigger Types:**
| Type | Description | Example Use Case |
|------|-------------|------------------|
| `SCHEDULED` | Automatically triggered by cron/interval | Daily backup at 2 AM |
| `MANUAL` | User manually triggered the schedule | Admin clicked "Run Now" |
| `CATCH_UP` | Backfill for missed executions | Server was down, catching up missed runs |
| `API` | Triggered via API call | External system triggered workflow |

**Execution Status:**
| Status | Description |
|--------|-------------|
| `PENDING` | Execution scheduled, waiting to start |
| `RUNNING` | Workflow is currently executing |
| `COMPLETED` | Workflow finished successfully |
| `FAILED` | Workflow failed |
| `SKIPPED` | Execution skipped (e.g., previous run still running) |

---

### Real-World Example: Daily Backup Schedule

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                         EXAMPLE: DAILY DATABASE BACKUP SCHEDULE                              │
└─────────────────────────────────────────────────────────────────────────────────────────────┘

1. SCHEDULE DEFINITION (workflow_schedules)
   ┌─────────────────────────────────────────────────────────────────────────────────────────┐
   │  id:              sch-backup-daily-001                                                  │
   │  workflow_id:     wf-db-backup-001                                                      │
   │  name:            "Daily PostgreSQL Backup"                                             │
   │  cron_expression: "0 2 * * *"              ← Every day at 2:00 AM                       │
   │  timezone:        "Asia/Ho_Chi_Minh"                                                    │
   │  schedule_type:   CRON                                                                  │
   │  status:          ACTIVE                                                                │
   │  next_run_at:     2025-12-13 02:00:00                                                   │
   │  last_run_id:     run-backup-20251212                                                   │
   └─────────────────────────────────────────────────────────────────────────────────────────┘

2. EXECUTION HISTORY (schedule_executions) - Last 5 runs
   ┌───────────────────┬──────────────────────────┬──────────────────────────┬─────────────┐
   │ scheduled_time    │ actual_time              │ trigger_type             │ status      │
   ├───────────────────┼──────────────────────────┼──────────────────────────┼─────────────┤
   │ 2025-12-12 02:00  │ 2025-12-12 02:00:03      │ SCHEDULED                │ COMPLETED   │
   │ 2025-12-11 02:00  │ 2025-12-11 02:00:01      │ SCHEDULED                │ COMPLETED   │
   │ 2025-12-10 02:00  │ 2025-12-10 02:00:05      │ SCHEDULED                │ COMPLETED   │
   │ 2025-12-09 02:00  │ 2025-12-09 08:30:00      │ CATCH_UP                 │ COMPLETED   │
   │ 2025-12-08 02:00  │ 2025-12-08 02:00:02      │ SCHEDULED                │ FAILED      │
   └───────────────────┴──────────────────────────┴──────────────────────────┴─────────────┘
   
   Note: On Dec 9, the server was down at 2 AM, so catch-up ran at 8:30 AM
         On Dec 8, the backup failed (maybe disk full)

3. TRIGGERED WORKFLOW RUN (workflow_runs)
   ┌─────────────────────────────────────────────────────────────────────────────────────────┐
   │  id:          run-backup-20251212                                                       │
   │  workflow_id: wf-db-backup-001                                                          │
   │  status:      COMPLETED                                                                 │
   │  started_at:  2025-12-12 02:00:03                                                       │
   │  finished_at: 2025-12-12 02:15:47                                                       │
   └─────────────────────────────────────────────────────────────────────────────────────────┘

4. TASK RUNS (task_runs) - Tasks in the workflow
   ┌────────────────────────┬──────────────────────────┬─────────────┬───────────────────────┐
   │ task_id                │ task_name                │ status      │ duration              │
   ├────────────────────────┼──────────────────────────┼─────────────┼───────────────────────┤
   │ backup_postgres        │ Backup PostgreSQL        │ SUCCESS     │ 10 min                │
   │ compress_backup        │ Compress Backup File     │ SUCCESS     │ 3 min                 │
   │ upload_s3              │ Upload to S3             │ SUCCESS     │ 2 min                 │
   │ notify_slack           │ Send Slack Notification  │ SUCCESS     │ 1 sec                 │
   └────────────────────────┴──────────────────────────┴─────────────┴───────────────────────┘
```

---

### Example: Job Market Data Crawling Schedule

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                      EXAMPLE: JOB MARKET CRAWLING SCHEDULES                                  │
└─────────────────────────────────────────────────────────────────────────────────────────────┘

SCHEDULES:
┌──────────────────────┬─────────────────────────────┬────────────────┬───────────────────────┐
│ name                 │ workflow                    │ cron           │ description           │
├──────────────────────┼─────────────────────────────┼────────────────┼───────────────────────┤
│ LinkedIn Crawl       │ wf-linkedin-crawler         │ 0 */4 * * *    │ Every 4 hours         │
│ TopCV Crawl          │ wf-topcv-crawler            │ 0 */6 * * *    │ Every 6 hours         │
│ ITViec Crawl         │ wf-itviec-crawler           │ 0 8,20 * * *   │ 8 AM and 8 PM daily   │
│ AI Processing        │ wf-ai-job-processor         │ 0 * * * *      │ Every hour            │
│ Data Aggregation     │ wf-aggregate-jobs           │ 0 0 * * *      │ Daily at midnight     │
│ Report Generation    │ wf-weekly-report            │ 0 9 * * 1      │ Monday 9 AM           │
└──────────────────────┴─────────────────────────────┴────────────────┴───────────────────────┘

WORKFLOW DAG (wf-linkedin-crawler):
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                             │
│    ┌────────────────┐                                                                       │
│    │  fetch_pages   │  ← Crawl job listing pages                                            │
│    └───────┬────────┘                                                                       │
│            │                                                                                │
│            ▼                                                                                │
│    ┌────────────────┐                                                                       │
│    │  parse_html    │  ← Extract job details from HTML                                      │
│    └───────┬────────┘                                                                       │
│            │                                                                                │
│            ▼                                                                                │
│    ┌────────────────┐                                                                       │
│    │  ai_extract    │  ← Pass to AI model for structured data                               │
│    └───────┬────────┘                                                                       │
│            │                                                                                │
│     ┌──────┴──────┐                                                                         │
│     ▼             ▼                                                                         │
│ ┌─────────┐  ┌─────────────┐                                                                │
│ │save_db  │  │notify_admin │                                                                │
│ └─────────┘  └─────────────┘                                                                │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```
