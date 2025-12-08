Week 1: Make It Usable
Right now you can submit workflows, but you can't see what's happening. Focus on:
Query APIs (Day 1-2)
GET /api/workflows - List all workflows
GET /api/workflows/{id} - Get workflow status
GET /api/workflows/{id}/tasks - See task details
Error Handling (Day 3-4)
Implement retry logic (you have maxRetries in DB but not using it)
Global exception handler
Workflow timeouts
Dead letter queue for permanent failures
Manual Controls (Day 5)
POST /api/workflows/{id}/cancel - Stop stuck workflows
POST /api/workflows/{id}/tasks/{taskId}/retry - Retry failed tasks
By end of Week 1: You can submit, view, cancel, and control workflows
<hr></hr>
Week 2: Production Basics
Configuration - Move hardcoded localhost:5432 to environment variables
Monitoring - Add Spring Boot Actuator for health checks
Integration Tests - Automated tests for end-to-end workflows
Docker Compose Updates - Worker scaling, health checks

By end of Week 2: Platform is deployable and observable
<hr></hr>
CRITICAL FOR YOUR CRAWLER (Week 3-4)
Week 3: Multi-Executor Support
You only have shell executor. Add:
HTTP Executor (Day 1-2) - Call OpenAI API for job data extraction
Python Executor (Day 3-4) - Run your crawler scripts
Docker Executor (Day 5) - Isolated execution
YAML Example After Week 3:

name: "Job Market Crawler"
tasks:
- id: "crawl-linkedin"
  type: "PYTHON"
  config:
  script_path: "/opt/crawlers/linkedin.py"
  args: ["--max-pages", "10"]

- id: "ai-extract"
  type: "HTTP"
  config:
  url: "https://api.openai.com/v1/chat/completions"
  method: "POST"
  headers:
  Authorization: "Bearer ${OPENAI_KEY}"
  body: '{"model": "gpt-4", "messages": [...]}'
  depends_on: ["crawl-linkedin"]

- id: "store-db"
  type: "PYTHON"
  config:
  script_path: "/opt/crawlers/store_to_db.py"
  depends_on: ["ai-extract"]

<hr></hr>
Week 4: Workflow Automation
Task Output & Variable Passing - Pass crawled data between tasks
Cron Scheduling - Run crawler daily at 2 AM automatically
Batch Processing - Process 10,000 jobs in parallel
By end of Week 4: Crawler runs fully automated end-to-end
<hr></hr>
POLISH & DEPLOY (Week 5-8)
Week 5: Better REST API (JSON submission, OpenAPI docs)
Week 6: Python client library for easy integration
Week 7: Production Docker setup with Flyway migrations
Week 8: Load testing, documentation, final polish
<hr></hr>
📋 THIS WEEK'S ACTION PLAN
Monday (Today)

// Create WorkflowQueryController.java

@RestController
@RequestMapping("/api/workflows")
public class WorkflowQueryController {

    @GetMapping
    public Page<WorkflowRunDTO> listWorkflows(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String status
    ) {
        // Query workflowRunRepository with pagination
    }
    
    @GetMapping("/{runId}")
    public WorkflowRunDTO getWorkflow(@PathVariable String runId) {
        // Return workflow + all tasks
    }
}

Tuesday
Implement retry logic in worker service
Add maxRetries check before re-queuing
Wednesday
Add cancel endpoint
Implement workflow timeout mechanism
Thursday-Friday
Externalize configuration (environment variables)
Add Spring Boot Actuator
Write integration tests
<hr></hr>
KEY DIFFERENCES FROM SECURITY-FIRST APPROACH
Security-First (10 weeks)
Core-First (8 weeks)
Week 1-2: SSO, RBAC, policies
Week 1-2: Query APIs, error handling
Week 3-4: Vault, audit logs
Week 3-4: HTTP/Python/Docker executors
Week 5-6: Security testing
Week 5-6: API polish, client SDK
Week 7-8: Pen testing, training
Week 7-8: Deployment, load testing
Result: Secure but limited features
Result: Fully functional, add security later
<hr></hr>
WHEN TO ADD SECURITY

Add security after Week 8 when:
✅ Platform works end-to-end for crawlers
✅ All executors implemented
✅ Scheduling & automation working
✅ Performance validated
✅ Documentation complete
Then spend 4-6 weeks adding:
SSO authentication
RBAC authorization
Secrets vault (HashiCorp Vault)
Audit logging
API security
<hr></hr>
IMMEDIATE NEXT STEP (30 minutes)
Open CORE_ROADMAP.md (just created)
Review Week 1 tasks
Create a new branch: git checkout -b feature/query-apis
Start coding the listWorkflows() endpoint
You now have a clear 8-week execution plan. Start with Day 1 tasks tomorrow!

controller/ - @WebMvcTest for REST endpoints
service/ - Unit tests with mocked dependencies
repository/ - @DataJpaTest for database queries
integration/ - @SpringBootTest for full workflow tests
testcontainers/ - Real PostgreSQL/RabbitMQ tests