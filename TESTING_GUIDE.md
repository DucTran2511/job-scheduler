# Testing Strategy Guide - Job Scheduler

## Overview

Your project now has **3 types of tests**:

### 1. **Unit Tests** (with Mockito)
- **What**: Tests individual components in isolation using mocks
- **Technologies**: JUnit 5, Mockito, AssertJ
- **Example**: `WorkflowServiceTest.java`
- **When to use**: Testing business logic without external dependencies

### 2. **Testcontainers Integration Tests** ⭐ **RECOMMENDED**
- **What**: Integration tests with REAL Docker containers
- **Technologies**: Testcontainers, PostgreSQL 16, Redis 7
- **Examples**: 
  - `WorkflowRepositoryIntegrationTest.java`
  - `TaskRunRepositoryIntegrationTest.java`
  - `WorkflowRunRepositoryIntegrationTest.java`
  - `RedisStreamsIntegrationTest.java`
- **When to use**: Testing database operations, Redis streams, real data persistence

### 3. **Controller Tests** (MockMvc)
- **What**: Tests REST API endpoints without starting full server
- **Technologies**: MockMvc, Spring Test
- **Example**: `WorkflowControllerTest.java`
- **When to use**: Testing API endpoints, request/response handling

---

## 🎯 Answer: Do You Need Both Integration and Container Tests?

**NO - Use TESTCONTAINERS (they ARE integration tests)**

**Testcontainers = Integration Tests with Real Containers**

For your job scheduler, you should focus on **Testcontainers** because:

✅ Tests against REAL PostgreSQL 16 (not H2 or mocks)  
✅ Tests against REAL Redis 7 Streams  
✅ Catches database-specific issues (SQL dialect, constraints)  
✅ Gives production-like confidence  
✅ Automatically manages container lifecycle  

---

## 📁 Your Current Test Structure

```
api-service/src/test/java/com/api/
├── controller/
│   └── WorkflowControllerTest.java          # MockMvc tests
├── service/
│   └── WorkflowServiceTest.java             # Unit tests with Mockito
└── testcontainers/                          # ⭐ Integration tests
    ├── WorkflowRepositoryIntegrationTest.java      # PostgreSQL tests
    ├── TaskRunRepositoryIntegrationTest.java       # PostgreSQL tests
    ├── WorkflowRunRepositoryIntegrationTest.java   # PostgreSQL tests
    └── RedisStreamsIntegrationTest.java            # Redis Streams tests
```

---

## 🚀 How to Run Tests

### Run ALL tests:
```bash
./mvnw test
```

### Run only Testcontainers tests:
```bash
./mvnw test -Dtest="*IntegrationTest"
```

### Run specific Testcontainer test:
```bash
./mvnw test -Dtest=WorkflowRepositoryIntegrationTest
./mvnw test -Dtest=RedisStreamsIntegrationTest
```

### Run unit tests only (no containers):
```bash
./mvnw test -Dtest=WorkflowServiceTest
```

### Run controller tests:
```bash
./mvnw test -Dtest=WorkflowControllerTest
```

---

## 🐳 What Testcontainers Does

When you run a Testcontainer test:

1. **Automatically pulls Docker images** (postgres:16, redis:7)
2. **Starts containers** before tests
3. **Configures Spring** to use container databases
4. **Runs your tests** against REAL databases
5. **Stops containers** after tests
6. **Reuses containers** across tests for speed

---

## 📊 Test Coverage Summary

### WorkflowRepositoryIntegrationTest (11 tests) ✅
- ✅ Save, find, update, delete workflows
- ✅ Count, exists checks
- ✅ Batch operations
- ✅ Real PostgreSQL constraints (unique name)
- ✅ Workflow with raw YAML/JSON definition

### TaskRunRepositoryIntegrationTest (13 tests) ✅
- ✅ Custom queries (findByWorkflowRunId, findByTaskId, findByStatus)
- ✅ Task lifecycle (PENDING → RUNNING → SUCCESS/FAILED)
- ✅ Retry logic tracking
- ✅ Timestamp tracking
- ✅ Multiple workflow runs support
- ✅ Job market crawler scenario (LinkedIn, TopCV, ITviec)

### WorkflowRunRepositoryIntegrationTest (10 tests) ✅
- ✅ Workflow run lifecycle
- ✅ Status transitions (RUNNING → COMPLETED/FAILED)
- ✅ Relationship with WorkflowEntity
- ✅ Multiple runs per workflow
- ✅ Lazy loading verification

### RedisStreamsIntegrationTest (13 tests) ✅
- ✅ Add/read messages from streams
- ✅ Consumer groups creation and management
- ✅ Message acknowledgment
- ✅ Pending messages tracking
- ✅ Multiple consumers handling
- ✅ Job queue scenario (LinkedIn, TopCV, ITviec crawling)
- ✅ Task retry simulation
- ✅ Complete workflow simulation with AI model integration
- ✅ Blocking read with async producer

**Total: 47 Integration Tests with Real PostgreSQL 16 & Redis 7**

---

## 🎓 Technologies Used in Tests

### Testing Frameworks:
- **JUnit 5** - Modern testing framework
- **AssertJ** - Fluent assertions (`assertThat()`)
- **Mockito** - Mocking framework for unit tests

### Integration Testing:
- **Testcontainers** - Docker containers for integration tests
- **@DataJpaTest** - Spring Boot slice for repository tests
- **@SpringBootTest** - Full Spring context for Redis tests

### Mocking:
- **@Mock** - Creates mock objects
- **@InjectMocks** - Injects mocks into test subject
- **MockMvc** - Mocks HTTP requests to controllers

---

## 💡 Recommendations

### For Beginners:
1. ✅ **Start with Unit Tests** (WorkflowServiceTest) - easiest to understand
2. ✅ **Then Controller Tests** (WorkflowControllerTest) - test your APIs
3. ✅ **Finally Testcontainers** - most powerful but requires Docker

### For Your Job Scheduler:
**Focus on Testcontainers** because:
- Your app heavily uses PostgreSQL and Redis
- You need confidence in database queries
- Redis Streams are critical for job queueing
- Testcontainers catch real-world issues

### Best Practices:
- ✅ Run Testcontainers in CI/CD pipeline
- ✅ Use `@BeforeEach` to clean data between tests
- ✅ Test both success and failure scenarios
- ✅ Keep tests independent (don't rely on execution order)

---

## 🔧 Prerequisites

### For Testcontainers:
- **Docker must be running** on your machine
- Docker daemon accessible (check with `docker ps`)
- Sufficient disk space for images (~500MB for postgres:16 + redis:7)

### For All Tests:
- Java 21
- Maven
- Internet connection (first run downloads dependencies)

---

## 📝 Quick Start Example

Run your first Testcontainer test:

```bash
cd /mnt/data/projects/java/job-scheduler/api-service
./mvnw test -Dtest=RedisStreamsIntegrationTest
```

This will:
1. Download Redis 7 image (if not present)
2. Start Redis container
3. Run 13 tests against real Redis
4. Show you exactly how Redis Streams work
5. Stop container

---

## 🎯 Final Answer

**You should use TESTCONTAINERS** - they are the best integration tests for your project.

**Don't think of it as "integration tests OR container tests"** - Testcontainers ARE integration tests that use real containers instead of mocks.

**Test Pyramid for Your Job Scheduler:**
```
        /\
       /  \        Few: E2E Tests (manual/Postman)
      /    \
     /------\      Some: Testcontainers (PostgreSQL, Redis)
    /        \
   /----------\    Many: Unit Tests (Services, Utils)
  /______________\
```

Your current setup is PERFECT for a production-ready job scheduler! 🚀
