# Core Platform Roadmap - Job Scheduler
## 🎯 Focus: Core Capabilities First, Security Later

**Philosophy**: Build a **fully functional workflow orchestration platform** that works end-to-end for your job market crawler use case, then add security as a separate layer.

---

## 📊 Current State Assessment

### ✅ What You Have (Working)
- ✅ YAML-based workflow definition
- ✅ DAG parsing & cycle detection (using JGraphT)
- ✅ PostgreSQL persistence (WorkflowEntity, WorkflowRun, TaskRun)
- ✅ Redis-based task queue (RedisPublisher for task distribution)
- ✅ Worker service (consumes tasks from Redis)
- ✅ Basic shell command executor
- ✅ Dependency resolution (root tasks → dependent tasks)
- ✅ Task completion callback
- ✅ Docker Compose setup (Postgres + Redis)

### ⚠️ What's Missing (Core Gaps)
- ❌ **No query APIs** (can't view workflow status after submission)
- ❌ **No retry logic** (tasks fail permanently)
- ❌ **No error handling** (workflows crash silently)
- ❌ **No monitoring/observability** (can't see what's running)
- ❌ **Only shell executor** (can't run HTTP, Docker, Python tasks)
- ❌ **No workflow cancellation** (workflows run forever if stuck)
- ❌ **No pagination** (will crash with 1000s of workflows)
- ❌ **No integration tests** (don't know if it actually works)
- ❌ **No production configuration** (hardcoded localhost)

---

## 🚀 REVISED CORE ROADMAP (Security Deferred)

## Phase 1: Make It Usable (Week 1-2) - **START HERE**

### **Week 1: Query APIs & Visibility**
**Goal**: Be able to see what your platform is doing

#### Day 1-2: Implement Query Endpoints
**Tasks:**
1. **List All Workflows** - `GET /api/workflows`
   - Return all workflows with basic info
   - Add pagination (page, size parameters)
   - Filter by status (RUNNING, COMPLETED, FAILED)

2. **Get Workflow Details** - `GET /api/workflows/{runId}`
   - Return workflow run details
   - Include all task statuses
   - Show current execution state

3. **Get Task Details** - `GET /api/workflows/{runId}/tasks`
   - List all tasks for a workflow
   - Show dependencies
   - Display execution logs (if available)

**Deliverable**: You can now see what workflows are running via API

---

#### Day 3-4: Error Handling & Resilience
**Tasks:**
1. **Global Exception Handler**
   - Catch all exceptions in controllers
   - Return standardized error responses
   - Log errors with context

2. **Retry Logic for Tasks**
   - Implement exponential backoff
   - Respect `maxRetries` from YAML
   - Track retry count in TaskRun entity

3. **Workflow Timeout**
   - Add timeout configuration per workflow
   - Auto-fail workflows exceeding timeout
   - Cleanup stuck workflows

4. **Dead Letter Queue**
   - Move permanently failed tasks to DLQ
   - Allow manual inspection
   - Support retry from DLQ

**Deliverable**: Platform handles failures gracefully

---

#### Day 5: Manual Control Operations
**Tasks:**
1. **Cancel Workflow** - `POST /api/workflows/{runId}/cancel`
   - Stop all running tasks
   - Mark workflow as CANCELLED
   - Cleanup resources

2. **Retry Failed Task** - `POST /api/workflows/{runId}/tasks/{taskId}/retry`
   - Reset task status to PENDING
   - Re-enqueue to Redis
   - Increment retry counter

3. **Pause/Resume Workflow** (optional)
   - Temporarily stop new task scheduling
   - Resume from current state

**Deliverable**: You can control workflows manually

---

### **Week 2: Production Readiness Basics**

#### Day 1-2: Configuration Management
**Tasks:**
1. **Externalize Configuration**
   - Move credentials to environment variables
   - Create `application-prod.properties`
   - Docker Compose with env file support

2. **Connection Pooling**
   - Configure HikariCP properly
   - Set sensible pool sizes
   - Add connection timeout

3. **Redis Configuration**
   - Connection pool settings
   - Reconnection strategy
   - Sentinel support (optional)

**Deliverable**: Can deploy to different environments

---

#### Day 3-4: Monitoring & Observability
**Tasks:**
1. **Add Spring Boot Actuator**
   - Health checks (`/actuator/health`)
   - Metrics endpoint (`/actuator/metrics`)
   - Info endpoint with build version

2. **Custom Metrics**
   - Workflow submission rate
   - Task execution time
   - Worker queue depth
   - Failure rate

3. **Structured Logging**
   - Add correlation IDs
   - Log workflow/task context
   - JSON log format (for parsing)

4. **Simple Dashboard (Optional)**
   - Create HTML page showing metrics
   - Real-time workflow count
   - Recent failures

**Deliverable**: Can monitor platform health

---

#### Day 5: Integration Testing
**Tasks:**
1. **End-to-End Test Suite**
   - Submit workflow → Verify completion
   - Test failure scenarios
   - Test retry logic
   - Test cancellation

2. **Docker Compose Test Environment**
   - Automated test setup
   - Seed test data
   - Teardown after tests

**Deliverable**: Confidence that platform works

---

## Phase 2: Advanced Executors (Week 3-4)

### **Week 3: HTTP Executor (Critical for Crawlers)**
**Goal**: Call webhooks, APIs, AI services from workflows

#### Day 1-2: Design & Implementation
**Tasks:**
1. **Create HTTP Executor Interface**
   ```java
   public interface TaskExecutor {
       ExecutionResult execute(TaskContext context);
   }
   
   public class HttpExecutor implements TaskExecutor {
       // RestTemplate or WebClient
   }
   ```

2. **Support Multiple HTTP Methods**
   - GET, POST, PUT, DELETE, PATCH
   - Configurable headers
   - Request body support (JSON, form data)
   - Authentication (Bearer token, Basic auth)

3. **Update YAML Schema**
   ```yaml
   tasks:
     - id: "call-openai"
       type: "HTTP"
       config:
         url: "https://api.openai.com/v1/chat/completions"
         method: "POST"
         headers:
           Authorization: "Bearer ${OPENAI_API_KEY}"
           Content-Type: "application/json"
         body: |
           {
             "model": "gpt-4",
             "messages": [{"role": "user", "content": "Extract job data from: ${html}"}]
           }
         timeout_seconds: 60
       depends_on: ["crawl-linkedin"]
   ```

**Deliverable**: Can call external APIs from workflows

---

#### Day 3-4: Python Script Executor
**Goal**: Run Python crawlers, AI processing scripts

**Tasks:**
1. **Create Python Executor**
   - Execute Python scripts with arguments
   - Pass environment variables
   - Capture stdout/stderr
   - Return exit code

2. **Virtual Environment Support**
   - Specify Python version
   - Install dependencies (requirements.txt)
   - Isolated execution

3. **YAML Example**
   ```yaml
   tasks:
     - id: "crawl-linkedin"
       type: "PYTHON"
       config:
         script_path: "/opt/crawlers/linkedin_crawler.py"
         python_version: "3.11"
         requirements: "/opt/crawlers/requirements.txt"
         args: ["--max-pages", "10", "--output", "/tmp/linkedin_jobs.json"]
         env:
           LINKEDIN_EMAIL: "${LINKEDIN_EMAIL}"
           LINKEDIN_PASSWORD: "${LINKEDIN_PASSWORD}"
   ```

**Deliverable**: Can run Python crawlers

---

#### Day 5: Docker Executor (Isolated Execution)
**Goal**: Run tasks in isolated containers

**Tasks:**
1. **Docker Java Client Integration**
   - Add docker-java dependency
   - Container lifecycle management
   - Log streaming

2. **YAML Example**
   ```yaml
   tasks:
     - id: "process-images"
       type: "DOCKER"
       config:
         image: "my-company/image-processor:latest"
         command: ["python", "process.py", "--input", "/data"]
         volumes:
           - "/tmp/input:/data:ro"
         env:
           AWS_REGION: "us-east-1"
         memory_limit: "2GB"
         cpu_limit: "1.5"
   ```

**Deliverable**: Tasks run in isolated containers

---

### **Week 4: Workflow Features for Crawlers**

#### Day 1-2: Task Output & Variable Passing
**Goal**: Pass data between tasks (crawl → AI → database)

**Tasks:**
1. **Task Output Capture**
   - Store task output (stdout, file, JSON)
   - Query output from other tasks
   - Use in downstream tasks

2. **Variable Interpolation**
   ```yaml
   tasks:
     - id: "crawl"
       type: "PYTHON"
       config:
         script: "crawler.py"
         output_path: "/tmp/jobs.json"
     
     - id: "ai-process"
       type: "HTTP"
       config:
         url: "https://api.openai.com/v1/completions"
         body: |
           {
             "prompt": "Extract structured data: ${crawl.output}"
           }
       depends_on: ["crawl"]
     
     - id: "store-db"
       type: "PYTHON"
       config:
         script: "store_to_db.py"
         args: ["--data", "${ai-process.output}"]
       depends_on: ["ai-process"]
   ```

**Deliverable**: Data flows between tasks

---

#### Day 3-4: Scheduling & Cron Support
**Goal**: Run crawler daily/hourly automatically

**Tasks:**
1. **Add Quartz Scheduler**
   - Cron expression support
   - Timezone handling
   - Workflow template selection

2. **Schedule Management API**
   ```
   POST /api/schedules
   {
     "workflow_template": "job_market_crawler.yaml",
     "cron": "0 2 * * *",  // Daily at 2 AM
     "enabled": true
   }
   ```

3. **Schedule Entity & Repository**
   - Persist schedules
   - Track execution history
   - Prevent duplicate runs

**Deliverable**: Crawlers run automatically

---

#### Day 5: Batch Processing & Parallelization
**Goal**: Process 1000s of jobs in parallel

**Tasks:**
1. **Dynamic Task Generation**
   - Split large datasets into batches
   - Generate tasks at runtime
   - Fan-out / Fan-in pattern

2. **YAML Example**
   ```yaml
   tasks:
     - id: "crawl-all-sites"
       type: "PARALLEL_BATCH"
       config:
         batch_size: 100
         max_parallel: 10
         items: "${job_urls}"  # List of 1000s of URLs
         task_template:
           type: "HTTP"
           config:
             url: "${item}"
             method: "GET"
   ```

**Deliverable**: Can process thousands of jobs efficiently

---

## Phase 3: Developer Experience (Week 5-6)

### **Week 5: REST API v2 & Documentation**

#### Day 1-3: Enhanced API Design
**Tasks:**
1. **RESTful API Standards**
   - Proper HTTP status codes
   - HATEOAS links (optional)
   - Versioned endpoints (`/api/v2/...`)
   - Consistent error format

2. **New Endpoints**
   ```
   POST   /api/v2/workflows              # Submit workflow
   GET    /api/v2/workflows              # List (paginated)
   GET    /api/v2/workflows/{id}         # Get details
   PUT    /api/v2/workflows/{id}         # Update (if running)
   DELETE /api/v2/workflows/{id}         # Archive
   POST   /api/v2/workflows/{id}/cancel
   POST   /api/v2/workflows/{id}/retry
   
   GET    /api/v2/workflows/{id}/tasks
   POST   /api/v2/workflows/{id}/tasks/{taskId}/retry
   GET    /api/v2/workflows/{id}/logs
   
   POST   /api/v2/schedules
   GET    /api/v2/schedules
   DELETE /api/v2/schedules/{id}
   ```

3. **JSON-Based Workflow Submission**
   ```json
   {
     "name": "job_market_crawler",
     "description": "Daily job aggregation",
     "tasks": [
       {
         "id": "crawl-linkedin",
         "type": "PYTHON",
         "config": {
           "script_path": "/opt/crawlers/linkedin.py"
         }
       }
     ]
   }
   ```

**Deliverable**: Clean, documented API

---

#### Day 4-5: OpenAPI Documentation
**Tasks:**
1. **Add Springdoc OpenAPI**
   - Auto-generate API docs
   - Interactive Swagger UI
   - Example requests/responses

2. **Annotations**
   ```java
   @Operation(summary = "Submit a new workflow")
   @ApiResponses({
       @ApiResponse(responseCode = "201", description = "Workflow created"),
       @ApiResponse(responseCode = "400", description = "Invalid workflow definition")
   })
   @PostMapping("/api/v2/workflows")
   public ResponseEntity<WorkflowResponse> submitWorkflow(@RequestBody WorkflowRequest request)
   ```

3. **Deploy Swagger UI**
   - Accessible at `/swagger-ui.html`
   - Try-it-out functionality

**Deliverable**: Self-documenting API

---

### **Week 6: Client SDK & Examples**

#### Day 1-3: Python Client Library
**Goal**: Easy integration with your crawler scripts

**Tasks:**
1. **Create Python Package**
   ```python
   # job_scheduler_client.py
   
   class JobSchedulerClient:
       def __init__(self, base_url):
           self.base_url = base_url
       
       def submit_workflow(self, workflow_def):
           response = requests.post(
               f"{self.base_url}/api/v2/workflows",
               json=workflow_def
           )
           return response.json()
       
       def get_workflow_status(self, workflow_id):
           response = requests.get(
               f"{self.base_url}/api/v2/workflows/{workflow_id}"
           )
           return response.json()
       
       def wait_for_completion(self, workflow_id, timeout=3600):
           # Poll until complete
           pass
   ```

2. **Usage Example**
   ```python
   from job_scheduler_client import JobSchedulerClient
   
   client = JobSchedulerClient("http://localhost:8080")
   
   workflow = {
       "name": "daily_crawler",
       "tasks": [
           {
               "id": "crawl",
               "type": "PYTHON",
               "config": {"script_path": "crawler.py"}
           }
       ]
   }
   
   result = client.submit_workflow(workflow)
   workflow_id = result["workflow_id"]
   
   # Wait for completion
   client.wait_for_completion(workflow_id)
   print("Crawler finished!")
   ```

**Deliverable**: Easy Python integration

---

#### Day 4-5: Workflow Template Library
**Tasks:**
1. **Create Template Repository**
   - `/workflow-templates/`
   - Pre-built workflows for common use cases
   - Job market crawler template
   - ETL template
   - Video processing template

2. **Template Variables**
   ```yaml
   # template: job_crawler.yaml
   name: "Job Market Crawler - ${DATE}"
   tasks:
     - id: "crawl-${SITE}"
       type: "PYTHON"
       config:
         script_path: "/opt/crawlers/${SITE}_crawler.py"
         args: ["--date", "${DATE}", "--max-pages", "${MAX_PAGES}"]
   ```

3. **Template Instantiation API**
   ```
   POST /api/v2/workflows/from-template
   {
     "template_name": "job_crawler",
     "variables": {
       "SITE": "linkedin",
       "DATE": "2025-11-14",
       "MAX_PAGES": "10"
     }
   }
   ```

**Deliverable**: Reusable workflow templates

---

## Phase 4: Production Deployment (Week 7-8)

### **Week 7: Docker & Deployment**

#### Day 1-2: Production Dockerfiles
**Tasks:**
1. **Multi-Stage Build**
   ```dockerfile
   # api-service/Dockerfile
   FROM maven:3.9-eclipse-temurin-21 AS build
   WORKDIR /app
   COPY pom.xml .
   COPY src ./src
   RUN mvn clean package -DskipTests
   
   FROM eclipse-temurin:21-jre-alpine
   WORKDIR /app
   COPY --from=build /app/target/*.jar app.jar
   EXPOSE 8080
   ENTRYPOINT ["java", "-jar", "app.jar"]
   ```

2. **Environment-Based Configuration**
   - Use env vars for all config
   - No hardcoded values
   - Sensible defaults

3. **Health Checks**
   ```dockerfile
   HEALTHCHECK --interval=30s --timeout=3s \
     CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1
   ```

**Deliverable**: Production-ready Docker images

---

#### Day 3-4: Docker Compose Production Setup
**Tasks:**
1. **Complete Docker Compose**
   ```yaml
   version: '3.8'
   services:
     api-service:
       build: ./api-service
       ports:
         - "8080:8080"
       environment:
         DATABASE_URL: jdbc:postgresql://postgres:5432/jobdb
         REDIS_HOST: redis
       depends_on:
         - postgres
         - redis
     
     worker-1:
       build: ./worker-service
       environment:
         API_SERVICE_URL: http://api-service:8080
         REDIS_HOST: redis
       depends_on:
         - api-service
     
     worker-2:
       build: ./worker-service
       # Scale workers easily
     
     postgres:
       image: postgres:16
       volumes:
         - pgdata:/var/lib/postgresql/data
       environment:
         POSTGRES_PASSWORD: ${DB_PASSWORD}
     
     redis:
       image: redis:7
       command: redis-server --appendonly yes
       volumes:
         - redis-data:/data
   
   volumes:
     pgdata:
     redis-data:
   ```

2. **Environment File**
   ```bash
   # .env.prod
   DB_PASSWORD=strong_password_here
   REDIS_PASSWORD=redis_password_here
   ```

**Deliverable**: One-command deployment

---

#### Day 5: Database Migrations
**Tasks:**
1. **Add Flyway**
   ```xml
   <dependency>
       <groupId>org.flywaydb</groupId>
       <artifactId>flyway-core</artifactId>
   </dependency>
   ```

2. **Migration Scripts**
   ```sql
   -- V1__initial_schema.sql
   CREATE TABLE workflow_entity (...);
   CREATE TABLE workflow_run (...);
   CREATE TABLE task_run (...);
   
   -- V2__add_schedules.sql
   CREATE TABLE schedule (...);
   ```

3. **Disable Hibernate DDL Auto**
   ```properties
   spring.jpa.hibernate.ddl-auto=validate  # Production
   spring.flyway.enabled=true
   ```

**Deliverable**: Safe schema evolution

---

### **Week 8: Testing & Documentation**

#### Day 1-3: Load Testing
**Tasks:**
1. **JMeter Test Plan**
   - Submit 1000 workflows
   - Concurrent execution
   - Measure latency (P50, P95, P99)
   - Identify bottlenecks

2. **Performance Benchmarks**
   - Workflows/second throughput
   - Task execution latency
   - Database query performance
   - Redis queue latency

3. **Optimization**
   - Database indexing
   - Connection pool tuning
   - Caching strategies

**Deliverable**: Performance baseline

---

#### Day 4-5: Documentation Finalization
**Tasks:**
1. **README.md**
   - Quick start guide
   - Architecture overview
   - Deployment instructions

2. **API Documentation**
   - Complete OpenAPI spec
   - Example workflows
   - Error codes reference

3. **Operational Guide**
   - Monitoring setup
   - Troubleshooting common issues
   - Scaling guide
   - Backup/recovery procedures

**Deliverable**: Complete documentation

---

## 📋 8-Week Sprint Checklist

### Week 1: Usability ✅
- [ ] Query APIs (list, get details)
- [ ] Error handling & retries
- [ ] Cancel/retry operations

### Week 2: Production Basics ✅
- [ ] Environment configuration
- [ ] Monitoring & health checks
- [ ] Integration tests

### Week 3: HTTP & Python Executors ✅
- [ ] HTTP executor (for AI APIs)
- [ ] Python executor (for crawlers)
- [ ] Docker executor

### Week 4: Workflow Features ✅
- [ ] Task output & variables
- [ ] Scheduling (cron)
- [ ] Batch processing

### Week 5: API v2 ✅
- [ ] RESTful endpoints
- [ ] OpenAPI documentation
- [ ] JSON-based submission

### Week 6: Client SDK ✅
- [ ] Python client library
- [ ] Workflow templates
- [ ] Usage examples

### Week 7: Deployment ✅
- [ ] Production Dockerfiles
- [ ] Docker Compose setup
- [ ] Database migrations

### Week 8: Testing & Docs ✅
- [ ] Load testing
- [ ] Performance tuning
- [ ] Complete documentation

---

## 🎯 Success Criteria (After 8 Weeks)

### Functional Requirements
- ✅ Submit workflows via REST API
- ✅ Execute Python scripts, HTTP calls, Docker containers
- ✅ View workflow status in real-time
- ✅ Schedule recurring workflows (cron)
- ✅ Retry failed tasks automatically
- ✅ Cancel stuck workflows manually
- ✅ Process 1000+ jobs in parallel
- ✅ Pass data between tasks

### Non-Functional Requirements
- ✅ Handle 100 concurrent workflows
- ✅ <5 second workflow submission latency
- ✅ >95% task success rate (with retries)
- ✅ Health checks & metrics exposed
- ✅ One-command Docker deployment
- ✅ Complete API documentation

### Use Case Validation
- ✅ **Job Market Crawler**: Crawl 3 sites → AI processing → Store DB
  - Runs daily automatically
  - Processes 10,000 jobs/day
  - <2 hour total runtime
  - Handles failures gracefully

---

## 📊 Week-by-Week Priorities

| Week | Focus | Outcome |
|------|-------|---------|
| **1** | Visibility & Control | Can see and manage workflows |
| **2** | Production Basics | Can deploy reliably |
| **3** | Executors | Can run real workloads (crawlers, AI) |
| **4** | Workflow Features | Automation & data flow |
| **5** | API Quality | Clean developer experience |
| **6** | Client SDK | Easy integration |
| **7** | Deployment | Production-ready infrastructure |
| **8** | Validation | Tested and documented |

---

## 🚀 After 8 Weeks: Next Steps

### Phase 5: Advanced Features (Optional)
- Web UI dashboard (React)
- Workflow versioning
- Conditional execution (if/else)
- Parallel branches (fan-out/fan-in)
- Workflow composition (call other workflows)
- Multi-tenancy (teams, namespaces)

### Phase 6: Security (When Ready)
- SSO integration
- RBAC
- API authentication
- Secrets management
- Audit logging

### Phase 7: Scale & Performance
- Horizontal scaling
- Worker auto-scaling
- PostgreSQL replication
- Redis Sentinel/Cluster
- Kubernetes deployment

---

## 💡 Immediate Next Action (This Week)

### **Day 1 (Today): Query APIs**
1. Create `WorkflowQueryService`
2. Add `GET /api/workflows` endpoint (with pagination)
3. Add `GET /api/workflows/{id}` endpoint
4. Test with your existing workflows

### **Day 2: Error Handling**
1. Create `GlobalExceptionHandler`
2. Define custom exceptions
3. Implement retry logic in worker
4. Test failure scenarios

### **Day 3: Manual Controls**
1. Add `POST /api/workflows/{id}/cancel`
2. Implement workflow cancellation logic
3. Add task retry endpoint
4. Test manual operations

### **Day 4-5: Configuration & Monitoring**
1. Externalize configuration
2. Add Spring Boot Actuator
3. Create health checks
4. Set up structured logging

**End of Week 1 Goal**: You can submit, view, cancel, and monitor workflows

---

## 📞 Questions to Answer This Week

1. **Where will you deploy?**
   - [ ] Local development (Docker Compose)
   - [ ] Cloud VMs (AWS/GCP/Azure)
   - [ ] Kubernetes cluster

2. **How many workers needed?**
   - [ ] 1-2 workers (prototype)
   - [ ] 5-10 workers (production crawler)
   - [ ] Auto-scaling based on queue depth

3. **What's your data volume?**
   - [ ] 100s of workflows/day
   - [ ] 1000s of workflows/day
   - [ ] 10,000s of workflows/day

4. **When do you need this working?**
   - [ ] 2 weeks (MVP for crawler)
   - [ ] 2 months (full platform)
   - [ ] 3+ months (enterprise-ready)

---

**Start with Week 1, Day 1 tasks tomorrow! Focus on making the platform immediately useful for your crawler use case.**

