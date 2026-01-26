# Data Flow Documentation

Complete flow inventory covering all 10 flows in the Job Scheduler platform.

---

## Flow Summary

| Category | Count | Flows |
|----------|-------|-------|
| Core Execution | 3 | Start, Execute, Callback |
| Scheduling | 3 | Create, Auto-Trigger, Manual-Trigger |
| Query | 3 | List, Detail, Tasks |
| Management | 1 | Pause/Resume/Delete |
| **Total** | **10** | |

---

## Category 1: Core Workflow Execution Flows

### Flow 1: Workflow Start

```
POST /api/workflows/start → WorkflowController → WorkflowOrchestrator.startWorkflow()
```

| Step | Component | Action |
|------|-----------|--------|
| 1 | `DagParser` | Parse YAML/JSON → `DagDefinition` |
| 2 | `DagParser` | Build DAG graph, validate cycles |
| 3 | `WorkflowRepository` | Persist `WorkflowEntity` to PostgreSQL |
| 4 | `WorkflowRunRepository` | Create `WorkflowRun` (status=RUNNING) |
| 5 | `TaskRunRepository` | Create `TaskRun` for each task (status=PENDING) |
| 6 | `RedisDagStore` | Cache DAG structure in Redis |
| 7 | `RedisDependencyTracker` | Initialize dependency tracking |
| 8 | `RedisPublisher` | Enqueue root tasks to `tasks_stream` |

---

### Flow 2: Task Execution (Worker Side)

```
Redis tasks_stream → TaskStreamConsumer → Executor → Callback
```

| Step | Component | Action |
|------|-----------|--------|
| 1 | `TaskStreamConsumer` | XREADGROUP from `tasks_stream` |
| 2 | `TaskStreamConsumer` | Acquire semaphore (max 1000 concurrent) |
| 3 | `ExecutorFactory` | Get appropriate executor for task type |
| 4 | `ShellTaskExecutor` | Execute bash command with timeout |
| 5 | `OrchestratorCallbackService` | Report completion to API |
| 6 | `TaskStreamConsumer` | ACK message in Redis |

---

### Flow 3: Task Completion Callback

```
POST /api/workflows/{runId}/tasks/{taskId}/complete → WorkflowOrchestrator.onTaskCompleted()
```

| Step | Component | Action |
|------|-----------|--------|
| 1 | `TaskRunRepository` | Find and validate task |
| 2a | Success | Update task → SUCCESS |
| 2b | Failure | Increment retry count |
| 3 | `RedisDependencyTracker` | Check which dependents are now ready |
| 4 | `RedisPublisher` | Enqueue ready dependent tasks |
| 5 | Check | If all tasks SUCCESS → Mark workflow COMPLETED |
| 6 | Cleanup | Delete DAG from Redis |

---

## Category 2: Scheduling Flows

### Flow 4: Schedule Creation

```
POST /api/schedules → ScheduleController → ScheduleService.createSchedule()
```

| Step | Component | Action |
|------|-----------|--------|
| 1 | `ScheduleService` | Validate request (cron, timezone) |
| 2 | `ScheduleService` | Convert Unix cron → Spring cron |
| 3 | `ScheduleService` | Calculate `nextRunAt` |
| 4 | `WorkflowScheduleRepository` | Persist `WorkflowSchedule` |

---

### Flow 5: Automatic Schedule Trigger (Cron)

```
@Scheduled → WorkflowScheduler.checkAndTriggerDueSchedules()
```

| Step | Component | Action |
|------|-----------|--------|
| 1 | `WorkflowScheduleRepository` | Find due schedules (`nextRunAt <= now`) |
| 2 | `DistributedLock` | Acquire lock (prevent duplicate triggers) |
| 3 | `WorkflowOrchestrator` | `startWorkflow()` with saved definition |
| 4 | `ScheduleExecutionRepository` | Record execution result |
| 5 | `WorkflowScheduler` | Calculate and update `nextRunAt` |
| 6 | `DistributedLock` | Release lock |

---

### Flow 6: Manual Schedule Trigger

```
POST /api/schedules/{id}/trigger → WorkflowScheduler.triggerManually()
```

| Step | Component | Action |
|------|-----------|--------|
| 1 | `WorkflowScheduleRepository` | Find schedule |
| 2 | `WorkflowOrchestrator` | `startWorkflow()` |
| 3 | `ScheduleExecutionRepository` | Record as MANUAL trigger |

---

## Category 3: Query Flows

### Flow 7: List Workflows

```
GET /api/workflows?page=0&size=20&status=RUNNING → WorkflowQueryController
```

| Step | Component | Action |
|------|-----------|--------|
| 1 | `WorkflowQueryService` | Parse pagination/filter params |
| 2 | `WorkflowRunRepository` | Query with pageable |
| 3 | `WorkflowRunDTO` | Map entities to DTOs |

---

### Flow 8: Get Workflow Details

```
GET /api/workflows/{id} → WorkflowQueryController
```

| Step | Component | Action |
|------|-----------|--------|
| 1 | `WorkflowQueryService` | Find by runId |
| 2 | Return | `WorkflowRunDetailDTO` with all task info |

---

### Flow 9: Get Workflow Tasks

```
GET /api/workflows/{id}/tasks → WorkflowQueryController
```

| Step | Component | Action |
|------|-----------|--------|
| 1 | `TaskRunRepository` | Find all tasks for workflow |
| 2 | Return | List of `TaskRunDTO` |

---

## Category 4: Schedule Management

### Flow 10: Schedule Lifecycle (Pause/Resume/Delete)

```
POST /api/schedules/{id}/pause | /resume | DELETE /api/schedules/{id}
```

| Operation | Action |
|-----------|--------|
| **Pause** | Set status → PAUSED, skip in scheduler |
| **Resume** | Set status → ACTIVE, recalculate `nextRunAt` |
| **Delete** | Remove schedule from database |

---

## End-to-End Data Flow Diagram

```
                                    ┌─────────────────────────────────────┐
                                    │           User / Client             │
                                    └───────────────┬─────────────────────┘
                                                    │
         ┌──────────────────────────────────────────┼──────────────────────────────────────────┐
         │                                          │                                          │
         ▼                                          ▼                                          ▼
┌─────────────────┐                     ┌─────────────────────┐                    ┌────────────────────┐
│ Flow 1: Start   │                     │ Flow 7-9: Query     │                    │ Flow 4: Schedule   │
│ POST /workflows │                     │ GET /workflows/*    │                    │ POST /schedules    │
│     /start      │                     │                     │                    │                    │
└────────┬────────┘                     └──────────┬──────────┘                    └─────────┬──────────┘
         │                                         │                                         │
         ▼                                         │                                         ▼
┌─────────────────────────┐                        │                          ┌──────────────────────────┐
│  WorkflowOrchestrator   │                        │                          │  ScheduleService         │
│  + DagParser            │◄───────────────────────┼───────────────────────────┤  + WorkflowScheduler     │
│  + RedisDagStore        │                        │                          │  + DistributedLock       │
└────────┬────────────────┘                        │                          └───────────┬──────────────┘
         │                                         │                                      │
         ▼                                         ▼                                      │
┌─────────────────────────────────────────────────────────────────────────────────────────┼───┐
│                                    PostgreSQL                                           │   │
│  ┌──────────────┐  ┌────────────┐  ┌──────────┐  ┌───────────────────┐  ┌─────────────┐│   │
│  │WorkflowEntity│  │WorkflowRun │  │ TaskRun  │  │ WorkflowSchedule  │  │ScheduleExec││   │
│  └──────────────┘  └────────────┘  └──────────┘  └───────────────────┘  └─────────────┘│   │
└─────────────────────────────────────────────────────────────────────────────────────────┼───┘
         │                                                                                │
         │ Publish tasks                                              Flow 5: Auto-Trigger│
         ▼                                                                   @Scheduled   │
┌─────────────────────────────────────────────────────────────────────────────────────────┼───┐
│                                       Redis                                             │   │
│  ┌───────────────────┐  ┌────────────────────────┐  ┌──────────────────────────────┐   │   │
│  │   tasks_stream    │  │  dag:{runId}:graph     │  │  schedule:{id}:lock          │◄──┘   │
│  │  (XADD → XREAD)   │  │  dag:{runId}:metadata  │  │  (Distributed Lock)          │       │
│  └─────────┬─────────┘  └────────────────────────┘  └──────────────────────────────┘       │
└────────────┼────────────────────────────────────────────────────────────────────────────────┘
             │
             │ XREADGROUP
             ▼
┌──────────────────────────────────────────────────────────────────┐
│                       Worker Service                              │
│  ┌───────────────────┐  ┌────────────────┐  ┌─────────────────┐  │
│  │ TaskStreamConsumer│─▶│ ExecutorFactory│─▶│ShellTaskExecutor│  │
│  │ (Virtual Threads) │  │                │  │ (bash -c ...)   │  │
│  └─────────┬─────────┘  └────────────────┘  └─────────────────┘  │
└────────────┼─────────────────────────────────────────────────────┘
             │
             │ Flow 3: Callback
             ▼
┌──────────────────────────────────────────────────────────────────┐
│  POST /api/workflows/task/callback                               │
│  WorkflowOrchestrator.onTaskCompleted()                          │
│  → Check dependencies → Enqueue ready tasks → Mark complete      │
└──────────────────────────────────────────────────────────────────┘
```
