# Job Scheduler - Distributed Workflow Orchestration Platform

A high-performance, distributed workflow engine built for scale and resilience. Unlike simple cron scripts or single-node schedulers, this system is designed to run across a cluster of nodes, handling failures and high concurrency with ease.

[![Java 21](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.6-brightgreen)](https://spring.io/projects/spring-boot)
[![Redis](https://img.shields.io/badge/Redis-7-red)](https://redis.io/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)](https://www.postgresql.org/)

---

## 🚀 Core Distributed Capabilities

### 1. Horizontal Scaling (Consumer Groups)
The system uses **Redis Streams Consumer Groups** to distribute tasks.
- **Load Balancing**: You can spin up 1, 10, or 100 `worker-service` instances. Redis automatically distributes pending tasks among them.
- **No "Master" Worker**: All workers are equal peers.
- **Implementation**: See `TaskStreamConsumer.java`. It uses `XREADGROUP` to pull tasks and `XACK` to acknowledge completion only after success.

### 2. Distributed Scheduling (Leaderless)
The `api-service` handles cron scheduling without a dedicated "leader" node.
- **Mechanism**: Uses **Redis Distributed Locks** (`SET NX PX`).
- **How it works**: When a schedule is due (e.g., every hour), all API instances wake up. Only *one* instance succeeds in acquiring the lock for that specific schedule. That instance triggers the workflow, while others stand down.
- **Benefit**: High availability. If one API node dies, others continue triggering schedules.

### 3. Fault Tolerance (Zombie Detection)
The system survives hard crashes (e.g., `kill -9`, OOM, Power Failure).
- **Heartbeats**: Running tasks send a heartbeat to Redis every 10 seconds.
- **Reaper**: The `ZombieTaskReaper` runs in the background. If a task stops heartbeating for >45s, it is marked as `FAILED` (Zombie).
- **Auto-Retry**: If the task has `maxRetries > 0`, the orchestrator automatically re-queues it, and a *healthy* worker picks it up.
| `GET` | `/actuator/metrics` | Prometheus metrics |

### Cron Scheduling

You can schedule workflows to run automatically using standard Cron expressions.

**Create a Schedule:**
```bash
curl -X POST http://localhost:80/api/schedules \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Daily Report",
    "cronExpression": "0 0 8 * * *",  # Run at 8:00 AM daily
    "timezone": "UTC",
    "workflowDefinition": "name: report\ntasks:\n  - id: t1\n    command: echo report"
  }'
```

**How it works:**
- The `api-service` uses **Distributed Locks** to ensure only *one* instance triggers the schedule, even if you have 10 API nodes running.

### 4. Real OS Process Isolation
Tasks are not just internal threads; they are real OS processes.
- **Shell Executor**: Uses `ProcessBuilder` to spawn a completely separate process (PID).
- **Control**: The worker monitors the PID, captures `stdout`/`stderr` in real-time, and enforces hard timeouts (killing the process tree if needed).

---

## 🛠️ How It Works

### The Lifecycle of a Job

1.  **Submission**: User POSTs a YAML workflow to `api-service`.
2.  **Parsing**: The DAG is parsed, validated, and persisted to PostgreSQL.
3.  **Queueing**: Root tasks (tasks with no dependencies) are pushed to the Redis Stream `tasks_stream`.
4.  **Execution**:
    - A `worker-service` instance pulls the message.
    - It spawns a child process (e.g., `bash -c "echo hello"`).
    - It streams logs and sends heartbeats.
5.  **Completion**:
    - Worker reports success to `api-service` via webhook.
    - `api-service` checks the DAG for dependent tasks.
    - If Task A finishes, and Task B depends on A, Task B is now enqueued.

---

## 📦 Quick Start

### 1. Start PostgreSQL and Redis
```bash
docker-compose up -d postgres redis
```

### 2. Build and Run
You can use the provided `Makefile` for easy management.

```bash
# Run single-node setup (Dev)
make up

# Run distributed cluster (Prod Simulation)
make cluster-up

# View logs
make logs          # for single node
make cluster-logs  # for cluster

# Stop everything
make down          # for single node
make cluster-down  # for cluster
```

### 3. Run the Cluster (Advanced)
To simulate a real production environment with **Load Balancing** and **Multiple Workers**:

```bash
# Starts:
# - 1 Nginx Load Balancer (Port 80)
# - 2 API Service Instances
# - 3 Worker Service Instances
# - PostgreSQL + Redis
docker-compose -f docker-compose.cluster.yml up -d --build
```

Access the API via Nginx at `http://localhost:80`.

### 3. Run Services (Manual Dev Mode)
You can run multiple instances to test distribution:

```bash
# Start API (Scheduler & Orchestrator)
./mvnw -pl api-service spring-boot:run

# Start Worker 1
./mvnw -pl worker-service spring-boot:run

# Start Worker 2 (in another terminal)
./mvnw -pl worker-service spring-boot:run
```

### 3. Submit a Workflow
```bash
curl -X POST http://localhost:8080/api/workflows/start \
  -H "Content-Type: text/plain" \
  --data-binary @- <<EOF
name: "distributed-demo"
tasks:
  - id: task-1
    name: "Heavy Computation"
    taskType: SHELL
    command: "sleep 10; echo 'Done'"
EOF
```

---

## 🔍 Monitoring & Observability

Since this is a distributed system, we provide tools to inspect the cluster state.

### Visualize Database
Run `./visualize-database.sh` to see:
- Active Workflow Runs
- Failed Tasks (with error logs)
- Queue Depth

### Check Worker Status
```bash
# See all consumers in the group
docker exec job-scheduler-redis redis-cli XINFO CONSUMERS tasks_stream worker-group
```

---

## 🧩 Configuration

| Service | Env Variable | Default | Description |
|---------|--------------|---------|-------------|
| **Common** | `REDIS_HOST` | localhost | Redis connection |
| **API** | `SCHEDULER_LOCK_TIMEOUT` | 300s | How long to hold schedule lock |
| **Worker** | `WORKER_MAX_CONCURRENT` | 1000 | Max virtual threads per worker |
| **Worker** | `WORKER_POLL_TIMEOUT` | 2s | Long-polling duration for Redis |

---

## 🏗️ Tech Stack

*   **Language**: Java 21 (Virtual Threads)
*   **Framework**: Spring Boot 3.5.6
*   **Orchestration**: JGraphT (DAG Management)
*   **Messaging**: Redis Streams
*   **Storage**: PostgreSQL 16
---

## Configuration

### Environment Variables

```bash
# Database
DB_HOST=localhost
DB_PORT=5432
DB_NAME=jobdb
DB_USER=jobuser
DB_PASSWORD=jobpass

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# Worker
WORKER_MAX_CONCURRENT=1000
WORKER_POLL_TIMEOUT_SECONDS=2
```

### Application Properties

```properties
# api-service/src/main/resources/application.properties
spring.datasource.url=jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}
spring.redis.host=${REDIS_HOST}
redis.stream.key=tasks_stream
```

---

## Technology Stack

| Component | Technology | Purpose |
|-----------|------------|---------|
| Language | Java 21 | Virtual Threads, modern APIs |
| Framework | Spring Boot 3.5.6 | Web, JPA, Redis, Actuator |
| Database | PostgreSQL 16 | Persistent storage |
| Message Queue | Redis 7 Streams | Task distribution |
| DAG Library | JGraphT 1.5.2 | Cycle detection, topological sort |
| Build | Maven | Multi-module management |
| Testing | JUnit 5, Testcontainers | Integration tests |

---

## Use Cases

This platform is designed for:

- **Web Crawling Pipelines** — Parallel scraping with AI data extraction
- **ETL Jobs** — Extract, transform, load with dependency management
- **CI/CD Pipelines** — Build → Test → Deploy workflows
- **Video Processing** — Transcode → Thumbnail → Upload
- **Data Backup** — Dump → Compress → Upload → Verify
- **ML Training** — Fetch data → Preprocess → Train → Evaluate

---

## Documentation

| Document | Description |
|----------|-------------|
| [API Reference](docs/api.md) | Full REST API reference |
| [Deployment Guide](docs/deployment.md) | Production deployment |
| [Architecture](docs/architecture.md) | Database schema and Redis architecture |
| [Workflow Examples](docs/examples.md) | Example YAML workflows |

---

## Roadmap

- [x] YAML workflow parsing with DAG validation
- [x] Redis Streams task queue with consumer groups
- [x] Shell command executor with timeout
- [x] Retry logic and error handling
- [x] Cron-based scheduling
- [x] Query APIs for workflow status
- [ ] HTTP executor for webhooks/APIs
- [ ] Python script executor
- [ ] Docker container executor
- [ ] Web dashboard with real-time updates
- [ ] Workflow templates and variables
- [ ] Multi-tenancy support

---
## Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## Author

Built with ☕ by Tran Hong Duc

*A portfolio project demonstrating distributed systems, workflow orchestration, and production-grade Java development.*