# Job Scheduler - Distributed Workflow Orchestration Platform

A distributed **DAG-based workflow orchestration platform** built with Java 21 and Spring Boot. Define complex workflows in YAML, execute tasks across distributed workers, and monitor progress in real-time.

[![Java Version](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.6-brightgreen)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-red)](https://redis.io/)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

---

## Features

- **YAML-Based Workflow Definition** — Define complex DAGs with dependencies, retries, and timeouts
- **Automatic Dependency Resolution** — Tasks execute in correct topological order with cycle detection
- **Distributed Worker Architecture** — Scale horizontally with Redis Streams consumer groups
- **High Concurrency** — Up to 1,000 concurrent tasks per worker using Java Virtual Threads
- **Built-in Retry Logic** — Configurable retries with automatic failure handling
- **Cron Scheduling** — Schedule recurring workflows with timezone support
- **Real-time Monitoring** — Query workflow status, task progress, and execution history
- **Production Ready** — Health checks, structured logging, and standardized error responses

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              User / API Client                           │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    │ REST API
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                           API Service (Port 8080)                        │
│  ┌─────────────────┐  ┌──────────────────┐  ┌───────────────────────┐   │
│  │ WorkflowController│ │ WorkflowOrchestrator│ │     DagParser       │   │
│  └───────┬─────────┘  └─────────┬────────┘  │  (JGraphT + YAML)     │   │
│          │                      │           └───────────────────────┘   │
│          │                      ▼                                       │
│  ┌───────▼───────────────────────────────────────────────────────────┐  │
│  │                        RedisPublisher                              │  │
│  │                    (XADD to tasks_stream)                          │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
        ┌────────────────────────────┼────────────────────────────────┐
        │                            │                                │
        ▼                            ▼                                ▼
┌───────────────┐          ┌───────────────┐                ┌───────────────┐
│   PostgreSQL  │          │     Redis     │                │    Worker     │
│               │          │   (Streams)   │◄───────────────│   Service(s)  │
│  • workflows  │          │               │  XREADGROUP    │               │
│  • runs       │          │ tasks_stream  │                │  • ShellExec  │
│  • tasks      │          │ dag:{runId}   │                │  • HTTPExec   │
│  • schedules  │          │ worker-group  │                │  • DockerExec │
└───────────────┘          └───────────────┘                └───────────────┘
```

---

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.9+
- Docker & Docker Compose

### 1. Clone and Start Infrastructure

```bash
git clone https://github.com/yourusername/job-scheduler.git
cd job-scheduler

# Start PostgreSQL and Redis
docker-compose up -d postgres redis
```

### 2. Build the Project

```bash
./mvnw clean install
```

### 3. Run Services

```bash
# Terminal 1: API Service
./mvnw -pl api-service spring-boot:run

# Terminal 2: Worker Service
./mvnw -pl worker-service spring-boot:run
```

### 4. Submit Your First Workflow

Create a file `hello-workflow.yaml`:

```yaml
name: hello_world
description: My first workflow
tasks:
  - id: greet
    name: Say Hello
    command: echo "Hello from Job Scheduler!"
    
  - id: timestamp
    name: Print Timestamp
    command: date
    depends_on: [greet]
```

Submit it:

```bash
curl -X POST http://localhost:8080/api/workflows/start \
  -H "Content-Type: text/plain" \
  --data-binary @hello-workflow.yaml
```

Check status:

```bash
curl http://localhost:8080/api/workflows/{workflow-run-id}
```

---

## Workflow Definition

### Basic Structure

```yaml
name: my_workflow
description: Optional description
tasks:
  - id: task_1           # Unique identifier (required)
    name: Task Name      # Display name
    command: echo "hi"   # Shell command to execute
    taskType: SHELL      # SHELL (default), HTTP, PYTHON, DOCKER
    max_retries: 3       # Retry on failure (default: 3)
    timeout_seconds: 300 # Timeout in seconds
    depends_on:          # List of task IDs this depends on
      - other_task
```

### Parallel Execution Example

```yaml
name: parallel_demo
tasks:
  # These run in parallel (no dependencies)
  - id: crawl_site_a
    command: python3 crawl.py --site a
    
  - id: crawl_site_b
    command: python3 crawl.py --site b
    
  - id: crawl_site_c
    command: python3 crawl.py --site c
    
  # This waits for all crawls to complete
  - id: merge_results
    command: python3 merge.py
    depends_on: [crawl_site_a, crawl_site_b, crawl_site_c]
    
  # Final step
  - id: notify
    command: curl -X POST $SLACK_WEBHOOK -d '{"text":"Done!"}'
    depends_on: [merge_results]
```

---

## API Reference

### Workflow Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/workflows/start` | Submit workflow (YAML body) |
| `GET` | `/api/workflows` | List all workflow runs |
| `GET` | `/api/workflows/{runId}` | Get workflow run details |
| `GET` | `/api/workflows/{runId}/tasks` | Get all tasks for a run |
| `POST` | `/api/workflows/{runId}/cancel` | Cancel a running workflow |

### Schedule Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/schedules` | Create scheduled workflow |
| `GET` | `/api/schedules` | List all schedules |
| `DELETE` | `/api/schedules/{id}` | Delete a schedule |

### Health Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/actuator/health` | Service health check |
| `GET` | `/actuator/metrics` | Prometheus metrics |

---

## Project Structure

```
job-scheduler/
├── pom.xml                     # Parent POM (multi-module)
├── docker-compose.yaml         # PostgreSQL + Redis + Services
│
├── common/                     # Shared module
│   └── src/main/java/com/common/
│       ├── dto/                # DagDefinition, TaskDef
│       └── enums/              # TaskType enum
│
├── api-service/                # Orchestration API
│   └── src/main/java/com/api/
│       ├── controller/         # REST endpoints
│       ├── orchestrator/       # Core workflow logic
│       ├── entity/             # JPA entities
│       ├── repository/         # Data access
│       ├── messaging/          # Redis publisher
│       └── scheduler/          # Cron scheduling
│
└── worker-service/             # Task executor
    └── src/main/java/com/worker/
        ├── service/            # Stream consumer
        ├── executor/           # ShellTaskExecutor, etc.
        └── model/              # ExecutionContext, Result
```

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