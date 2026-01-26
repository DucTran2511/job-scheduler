# System Architecture

High-level architecture of the Job Scheduler distributed workflow orchestration platform.

---

## Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              User / API Client                               │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │ REST API
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           API Service (Port 8080)                            │
│  ┌─────────────────────┐  ┌──────────────────────┐  ┌───────────────────┐   │
│  │ WorkflowController  │  │ WorkflowOrchestrator │  │     DagParser     │   │
│  │ ScheduleController  │  │ ScheduleService      │  │  (JGraphT + YAML) │   │
│  │ QueryController     │  │ WorkflowScheduler    │  │                   │   │
│  └──────────┬──────────┘  └──────────┬───────────┘  └───────────────────┘   │
│             │                        │                                       │
│  ┌──────────▼────────────────────────▼───────────────────────────────────┐  │
│  │                         RedisPublisher                                 │  │
│  │                      (XADD to tasks_stream)                            │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────┬────────────────────────────────────────┘
                                     │
         ┌───────────────────────────┼───────────────────────────┐
         │                           │                           │
         ▼                           ▼                           ▼
┌─────────────────┐         ┌─────────────────┐         ┌─────────────────┐
│   PostgreSQL    │         │      Redis      │         │ Worker Service  │
│                 │         │    (Streams)    │◄────────│   (Port 8081)   │
│  • workflows    │         │                 │ XREAD   │                 │
│  • runs         │         │  tasks_stream   │ GROUP   │  • ShellExecutor│
│  • tasks        │         │  dag:{runId}    │         │  • HTTPExecutor │
│  • schedules    │         │  lock:*         │         │  • Virtual Thds │
└─────────────────┘         └─────────────────┘         └─────────────────┘
```

---

## Components

### API Service
| Component | Responsibility |
|-----------|----------------|
| `WorkflowController` | REST endpoints for workflow CRUD |
| `WorkflowOrchestrator` | Core orchestration logic, DAG execution |
| `DagParser` | YAML parsing, cycle detection (JGraphT) |
| `ScheduleService` | Cron schedule management |
| `WorkflowScheduler` | Polls and triggers due schedules |
| `RedisPublisher` | Publishes tasks to Redis Streams |
| `RedisDependencyTracker` | Tracks task dependencies atomically |
| `DistributedLock` | Prevents duplicate schedule triggers |

### Worker Service
| Component | Responsibility |
|-----------|----------------|
| `TaskStreamConsumer` | Consumes from Redis Streams (XREADGROUP) |
| `ExecutorFactory` | Selects executor by task type |
| `ShellTaskExecutor` | Runs bash commands with timeout |
| `OrchestratorCallbackService` | Reports completion to API |

---

## Technology Stack

| Layer | Technology |
|-------|------------|
| Language | Java 21 (Virtual Threads) |
| Framework | Spring Boot 3.5.6 |
| Database | PostgreSQL 16 |
| Message Queue | Redis 7 Streams |
| DAG Library | JGraphT 1.5.2 |
| Build | Maven (multi-module) |

---

## Communication Patterns

```
┌──────────┐                    ┌──────────┐                    ┌──────────┐
│   API    │  ──── XADD ────▶   │  Redis   │  ◀── XREADGROUP ── │  Worker  │
│ Service  │                    │ Streams  │                    │ Service  │
└────┬─────┘                    └──────────┘                    └────┬─────┘
     │                                                               │
     │                      ◀── HTTP POST ───────────────────────────┘
     │                         (task callback)
     ▼
┌──────────┐
│PostgreSQL│
└──────────┘
```

1. **API → Redis**: `XADD tasks_stream` (publish tasks)
2. **Worker ← Redis**: `XREADGROUP` (consume tasks)
3. **Worker → API**: HTTP POST callback (report completion)
4. **API ↔ PostgreSQL**: JPA (persist state)

---

## Scalability

| Component | Scaling Strategy |
|-----------|------------------|
| API Service | Horizontal (stateless, load balanced) |
| Worker Service | Horizontal (consumer groups) |
| PostgreSQL | Vertical / Read replicas |
| Redis | Cluster mode / Sentinel |

Workers form a **consumer group** in Redis Streams, enabling:
- Automatic load distribution
- At-least-once delivery
- Message acknowledgment (XACK)
