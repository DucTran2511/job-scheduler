# Deployment Guide - Job Scheduler Platform

## Table of Contents
1. [Deployment Options](#deployment-options)
2. [Docker Compose Deployment](#docker-compose-deployment)
3. [Kubernetes Deployment](#kubernetes-deployment)
4. [Production Checklist](#production-checklist)
5. [Security Hardening](#security-hardening)
6. [Monitoring Setup](#monitoring-setup)
7. [Backup & Recovery](#backup--recovery)
8. [Troubleshooting](#troubleshooting)

---

## Deployment Options

### Comparison Matrix

| Feature | Docker Compose | Kubernetes | Standalone |
|---------|---------------|------------|------------|
| **Complexity** | Low | High | Medium |
| **Scalability** | Limited | Excellent | Manual |
| **High Availability** | No | Yes | Manual |
| **Cost** | Low | Medium-High | Low |
| **Best For** | Dev, Small Teams | Enterprise | Traditional Shops |

---

## Docker Compose Deployment

### Prerequisites

- Docker 20.10+
- Docker Compose 2.0+
- 4 GB RAM minimum
- 20 GB disk space

### Quick Start (Development)

```bash
# Clone repository
git clone https://github.com/yourorg/job-scheduler-platform
cd job-scheduler-platform

# Start services
docker-compose up -d

# Verify
curl http://localhost:8080/actuator/health
```

### Production Deployment

#### 1. Create Production Environment File

Create `.env.prod`:
```bash
# Database
DATABASE_URL=jdbc:postgresql://postgres:5432/jobdb
DATABASE_USERNAME=jobuser
DATABASE_PASSWORD=CHANGE_ME_STRONG_PASSWORD
DB_POOL_SIZE=20
DB_POOL_MIN_IDLE=5

# Redis
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=CHANGE_ME_REDIS_PASSWORD

# API Service
SERVER_PORT=8080
SPRING_PROFILES_ACTIVE=prod

# Worker Service
API_SERVICE_URL=http://api-service:8080
WORKER_CONCURRENCY=10

# Monitoring
ENABLE_METRICS=true
ENABLE_HEALTH_CHECKS=true
```

#### 2. Create Production Compose File

`docker-compose.prod.yaml`:
```yaml
version: '3.8'

services:
  api-service:
    image: yourorg/job-scheduler-api:1.0.0
    container_name: job-scheduler-api
    restart: always
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - DATABASE_URL=${DATABASE_URL}
      - DATABASE_USERNAME=${DATABASE_USERNAME}
      - DATABASE_PASSWORD=${DATABASE_PASSWORD}
      - REDIS_HOST=${REDIS_HOST}
      - REDIS_PORT=${REDIS_PORT}
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s
    networks:
      - job-scheduler-network
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"

  worker-service:
    image: yourorg/job-scheduler-worker:1.0.0
    restart: always
    deploy:
      replicas: 5
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - API_SERVICE_URL=${API_SERVICE_URL}
      - DATABASE_URL=${DATABASE_URL}
      - DATABASE_USERNAME=${DATABASE_USERNAME}
      - DATABASE_PASSWORD=${DATABASE_PASSWORD}
      - REDIS_HOST=${REDIS_HOST}
      - REDIS_PORT=${REDIS_PORT}
      - WORKER_CONCURRENCY=${WORKER_CONCURRENCY}
    depends_on:
      - api-service
    networks:
      - job-scheduler-network
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"

  postgres:
    image: postgres:16
    container_name: job-scheduler-postgres
    restart: always
    environment:
      POSTGRES_USER: ${DATABASE_USERNAME}
      POSTGRES_PASSWORD: ${DATABASE_PASSWORD}
      POSTGRES_DB: jobdb
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./backups:/backups
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DATABASE_USERNAME}"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - job-scheduler-network
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"

  redis:
    image: redis:7-alpine
    container_name: job-scheduler-redis
    restart: always
    command: redis-server --requirepass ${REDIS_PASSWORD} --appendonly yes
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "--raw", "incr", "ping"]
      interval: 10s
      timeout: 3s
      retries: 5
    networks:
      - job-scheduler-network
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"

  # Monitoring (Optional but recommended)
  prometheus:
    image: prom/prometheus:latest
    container_name: job-scheduler-prometheus
    restart: always
    volumes:
      - ./monitoring/prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus-data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
    ports:
      - "9090:9090"
    networks:
      - job-scheduler-network

  grafana:
    image: grafana/grafana:latest
    container_name: job-scheduler-grafana
    restart: always
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=CHANGE_ME_GRAFANA_PASSWORD
    volumes:
      - grafana-data:/var/lib/grafana
      - ./monitoring/grafana-dashboards:/etc/grafana/provisioning/dashboards
    ports:
      - "3000:3000"
    networks:
      - job-scheduler-network

networks:
  job-scheduler-network:
    driver: bridge

volumes:
  postgres-data:
  redis-data:
  prometheus-data:
  grafana-data:
```

#### 3. Deploy

```bash
# Load environment variables
export $(cat .env.prod | xargs)

# Deploy
docker-compose -f docker-compose.prod.yaml up -d

# Verify all services are healthy
docker-compose -f docker-compose.prod.yaml ps

# Check logs
docker-compose -f docker-compose.prod.yaml logs -f
```

#### 4. Scale Workers

```bash
# Scale to 10 workers
docker-compose -f docker-compose.prod.yaml up -d --scale worker-service=10

# Verify
docker-compose -f docker-compose.prod.yaml ps worker-service
```

---

## Kubernetes Deployment

### Prerequisites

- Kubernetes cluster 1.24+
- kubectl configured
- Helm 3.0+ (optional)

### Option 1: Using kubectl

#### 1. Create Namespace

```bash
kubectl create namespace job-scheduler
```

#### 2. Create Secrets

```bash
# Database credentials
kubectl create secret generic db-credentials \
  --from-literal=username=jobuser \
  --from-literal=password=STRONG_PASSWORD \
  -n job-scheduler

# Redis password
kubectl create secret generic redis-credentials \
  --from-literal=password=REDIS_PASSWORD \
  -n job-scheduler
```

#### 3. Deploy PostgreSQL

`k8s/postgres.yaml`:
```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: postgres-pvc
  namespace: job-scheduler
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 100Gi
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: postgres
  namespace: job-scheduler
spec:
  serviceName: postgres
  replicas: 1
  selector:
    matchLabels:
      app: postgres
  template:
    metadata:
      labels:
        app: postgres
    spec:
      containers:
      - name: postgres
        image: postgres:16
        env:
        - name: POSTGRES_USER
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: username
        - name: POSTGRES_PASSWORD
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: password
        - name: POSTGRES_DB
          value: jobdb
        ports:
        - containerPort: 5432
        volumeMounts:
        - name: postgres-storage
          mountPath: /var/lib/postgresql/data
  volumeClaimTemplates:
  - metadata:
      name: postgres-storage
    spec:
      accessModes: ["ReadWriteOnce"]
      resources:
        requests:
          storage: 100Gi
---
apiVersion: v1
kind: Service
metadata:
  name: postgres
  namespace: job-scheduler
spec:
  selector:
    app: postgres
  ports:
  - port: 5432
    targetPort: 5432
  clusterIP: None
```

#### 4. Deploy Redis

`k8s/redis.yaml`:
```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: redis
  namespace: job-scheduler
spec:
  serviceName: redis
  replicas: 1
  selector:
    matchLabels:
      app: redis
  template:
    metadata:
      labels:
        app: redis
    spec:
      containers:
      - name: redis
        image: redis:7-alpine
        command: ["redis-server"]
        args: ["--requirepass", "$(REDIS_PASSWORD)", "--appendonly", "yes"]
        env:
        - name: REDIS_PASSWORD
          valueFrom:
            secretKeyRef:
              name: redis-credentials
              key: password
        ports:
        - containerPort: 6379
        volumeMounts:
        - name: redis-storage
          mountPath: /data
  volumeClaimTemplates:
  - metadata:
      name: redis-storage
    spec:
      accessModes: ["ReadWriteOnce"]
      resources:
        requests:
          storage: 10Gi
---
apiVersion: v1
kind: Service
metadata:
  name: redis
  namespace: job-scheduler
spec:
  selector:
    app: redis
  ports:
  - port: 6379
    targetPort: 6379
```

#### 5. Deploy API Service

`k8s/api-service.yaml`:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: api-service
  namespace: job-scheduler
spec:
  replicas: 3
  selector:
    matchLabels:
      app: api-service
  template:
    metadata:
      labels:
        app: api-service
    spec:
      containers:
      - name: api-service
        image: yourorg/job-scheduler-api:1.0.0
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
        - name: DATABASE_URL
          value: "jdbc:postgresql://postgres:5432/jobdb"
        - name: DATABASE_USERNAME
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: username
        - name: DATABASE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: password
        - name: REDIS_HOST
          value: "redis"
        - name: REDIS_PORT
          value: "6379"
        - name: REDIS_PASSWORD
          valueFrom:
            secretKeyRef:
              name: redis-credentials
              key: password
        ports:
        - containerPort: 8080
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 5
        resources:
          requests:
            memory: "1Gi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "1000m"
---
apiVersion: v1
kind: Service
metadata:
  name: api-service
  namespace: job-scheduler
spec:
  selector:
    app: api-service
  ports:
  - port: 8080
    targetPort: 8080
  type: LoadBalancer
```

#### 6. Deploy Worker Service

`k8s/worker-service.yaml`:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: worker-service
  namespace: job-scheduler
spec:
  replicas: 10
  selector:
    matchLabels:
      app: worker-service
  template:
    metadata:
      labels:
        app: worker-service
    spec:
      containers:
      - name: worker-service
        image: yourorg/job-scheduler-worker:1.0.0
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
        - name: API_SERVICE_URL
          value: "http://api-service:8080"
        - name: DATABASE_URL
          value: "jdbc:postgresql://postgres:5432/jobdb"
        - name: DATABASE_USERNAME
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: username
        - name: DATABASE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: password
        - name: REDIS_HOST
          value: "redis"
        - name: REDIS_PORT
          value: "6379"
        - name: REDIS_PASSWORD
          valueFrom:
            secretKeyRef:
              name: redis-credentials
              key: password
        - name: WORKER_CONCURRENCY
          value: "5"
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "1Gi"
            cpu: "500m"
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: worker-service-hpa
  namespace: job-scheduler
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: worker-service
  minReplicas: 5
  maxReplicas: 50
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
```

#### 7. Apply All Manifests

```bash
kubectl apply -f k8s/postgres.yaml
kubectl apply -f k8s/redis.yaml
kubectl apply -f k8s/api-service.yaml
kubectl apply -f k8s/worker-service.yaml

# Verify
kubectl get all -n job-scheduler
```

### Option 2: Using Helm

(Coming soon - Helm chart will be published separately)

---

## Production Checklist

### Before Deployment

- [ ] Change all default passwords
- [ ] Review and set resource limits
- [ ] Configure backup strategy
- [ ] Set up monitoring alerts
- [ ] Test disaster recovery procedures
- [ ] Review security hardening steps
- [ ] Document deployment process
- [ ] Create runbook for common issues

### Database

- [ ] Enable connection pooling
- [ ] Configure appropriate pool size
- [ ] Set up automated backups
- [ ] Enable point-in-time recovery
- [ ] Create read replicas (if needed)
- [ ] Add indexes for common queries
- [ ] Set up database monitoring

### Redis

- [ ] Enable persistence (AOF + RDB)
- [ ] Configure password authentication
- [ ] Set max memory policy
- [ ] Enable Redis monitoring
- [ ] Consider Redis Cluster for HA

### Application

- [ ] Set appropriate JVM heap size
- [ ] Configure logging levels
- [ ] Enable health checks
- [ ] Set up application metrics
- [ ] Configure connection timeouts
- [ ] Review retry policies

### Networking

- [ ] Configure firewalls
- [ ] Set up load balancer
- [ ] Enable HTTPS/TLS
- [ ] Configure DNS
- [ ] Set up VPN (if needed)

---

## Security Hardening

### Network Security

```yaml
# Restrict API access to internal network only
services:
  api-service:
    ports:
      - "127.0.0.1:8080:8080"  # Localhost only
```

### Use Secrets Management

```bash
# Use Docker secrets
echo "strong_password" | docker secret create db_password -

# Reference in compose
services:
  postgres:
    secrets:
      - db_password
    environment:
      POSTGRES_PASSWORD_FILE: /run/secrets/db_password
```

### Enable TLS

```yaml
# In application-prod.properties
server.ssl.enabled=true
server.ssl.key-store=classpath:keystore.p12
server.ssl.key-store-password=${KEYSTORE_PASSWORD}
server.ssl.key-store-type=PKCS12
```

### Limit Worker Capabilities

```yaml
# docker-compose.yaml
worker-service:
  cap_drop:
    - ALL
  cap_add:
    - NET_BIND_SERVICE
  read_only: true
  security_opt:
    - no-new-privileges:true
```

---

## Monitoring Setup

### Prometheus Configuration

`monitoring/prometheus.yml`:
```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'job-scheduler-api'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['api-service:8080']
  
  - job_name: 'postgres'
    static_configs:
      - targets: ['postgres-exporter:9187']
  
  - job_name: 'redis'
    static_configs:
      - targets: ['redis-exporter:9121']
```

### Grafana Dashboards

Import dashboard ID: `dashboards/job-scheduler-overview.json`

**Key Metrics:**
- Active workflows
- Task execution rate
- Worker queue depth
- P95/P99 task duration
- Error rate
- Database connection pool
- Redis memory usage

---

## Backup & Recovery

### Automated PostgreSQL Backup

```bash
# Create backup script
cat > /opt/scripts/backup-postgres.sh << 'EOF'
#!/bin/bash
DATE=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR=/backups
docker exec job-scheduler-postgres pg_dump -U jobuser jobdb > $BACKUP_DIR/jobdb_$DATE.sql
gzip $BACKUP_DIR/jobdb_$DATE.sql
# Keep only last 7 days
find $BACKUP_DIR -name "jobdb_*.sql.gz" -mtime +7 -delete
EOF

chmod +x /opt/scripts/backup-postgres.sh

# Add to cron (daily at 2 AM)
0 2 * * * /opt/scripts/backup-postgres.sh
```

### Recovery Procedure

```bash
# Stop services
docker-compose down

# Restore database
gunzip < /backups/jobdb_20261113_020000.sql.gz | \
  docker exec -i job-scheduler-postgres psql -U jobuser jobdb

# Start services
docker-compose up -d
```

---

## Troubleshooting

See full troubleshooting guide in [PLATFORM_OVERVIEW.md](PLATFORM_OVERVIEW.md#troubleshooting)

**Quick Commands:**

```bash
# Check all services
docker-compose ps

# View logs
docker-compose logs -f api-service
docker-compose logs -f worker-service

# Restart service
docker-compose restart worker-service

# Check database
docker exec -it job-scheduler-postgres psql -U jobuser -d jobdb

# Check Redis
docker exec -it job-scheduler-redis redis-cli -a PASSWORD

# Health check
curl http://localhost:8080/actuator/health
```

---

**Your platform is now production-ready! 🚀**
# Workflow Definition Guide

## Table of Contents
1. [Introduction](#introduction)
2. [Basic Workflow Structure](#basic-workflow-structure)
3. [Task Definition](#task-definition)
4. [Dependencies & Parallelism](#dependencies--parallelism)
5. [Retry & Timeout Configuration](#retry--timeout-configuration)
6. [Advanced Patterns](#advanced-patterns)
7. [Best Practices](#best-practices)
8. [Validation & Debugging](#validation--debugging)

---

## Introduction

Workflows in Job Scheduler Platform are defined using YAML format. Each workflow is a Directed Acyclic Graph (DAG) of tasks with dependencies.

**Key Concepts:**
- **Workflow**: A collection of tasks with dependencies
- **Task**: A single unit of work (shell command)
- **DAG**: Defines execution order based on dependencies
- **Parallel Execution**: Independent tasks run simultaneously

---

## Basic Workflow Structure

### Minimal Workflow

```yaml
name: my_first_workflow
description: A simple workflow with one task
tasks:
  - id: task_1
    name: Print Hello
    command: echo "Hello World"
```

### Complete Workflow Template

```yaml
name: workflow_name                    # Required: Unique workflow identifier
description: What this workflow does   # Optional: Human-readable description

tasks:
  - id: unique_task_id                 # Required: Unique within workflow
    name: Human Readable Name          # Optional: Display name
    command: bash -c "echo hello"      # Required: Shell command to execute
    depends_on: []                     # Optional: List of parent task IDs
    max_retries: 3                     # Optional: Retry count (default: 3)
    timeout_seconds: 300               # Optional: Task timeout (default: none)
```

---

## Task Definition

### Required Fields

**id** (string)
- Must be unique within the workflow
- Used for dependency references
- Convention: snake_case

```yaml
- id: extract_data
```

**command** (string)
- Shell command to execute
- Runs in bash shell on worker
- Can be multi-line

```yaml
# Simple command
command: python3 script.py

# Multi-line command
command: |
  cd /tmp
  python3 download.py
  python3 process.py
```

### Optional Fields

**name** (string)
- Human-readable task name
- Used in UI and logs

```yaml
name: Extract Data from API
```

**depends_on** (array of strings)
- List of task IDs that must complete first
- Tasks run in parallel if no dependencies
- Creates DAG edges

```yaml
depends_on:
  - task_1
  - task_2
```

**max_retries** (integer)
- Number of retry attempts on failure
- Default: 3
- Set to 0 to disable retries

```yaml
max_retries: 5
```

**timeout_seconds** (integer)
- Maximum execution time
- Task fails if exceeded
- Default: no timeout

```yaml
timeout_seconds: 1800  # 30 minutes
```

---

## Dependencies & Parallelism

### Linear Pipeline (Sequential)

```yaml
name: linear_pipeline
tasks:
  - id: step_1
    command: echo "Step 1"
  
  - id: step_2
    depends_on: [step_1]
    command: echo "Step 2"
  
  - id: step_3
    depends_on: [step_2]
    command: echo "Step 3"
```

**Execution:** step_1 → step_2 → step_3

### Parallel Execution (Fan-Out)

```yaml
name: parallel_processing
tasks:
  - id: download_source
    command: wget https://data.example.com/file.zip
  
  # These 3 tasks run in parallel
  - id: process_batch_1
    depends_on: [download_source]
    command: python3 process.py --batch 1
  
  - id: process_batch_2
    depends_on: [download_source]
    command: python3 process.py --batch 2
  
  - id: process_batch_3
    depends_on: [download_source]
    command: python3 process.py --batch 3
  
  # Merge waits for all 3
  - id: merge_results
    depends_on:
      - process_batch_1
      - process_batch_2
      - process_batch_3
    command: python3 merge.py
```

**Execution:**
```
download_source
       │
   ┌───┼───┐
   │   │   │
batch_1 batch_2 batch_3
   │   │   │
   └───┼───┘
       │
  merge_results
```

### Diamond Pattern (Fork-Join)

```yaml
name: diamond_workflow
tasks:
  - id: start
    command: echo "Starting"
  
  - id: branch_a
    depends_on: [start]
    command: python3 process_a.py
  
  - id: branch_b
    depends_on: [start]
    command: python3 process_b.py
  
  - id: join
    depends_on: [branch_a, branch_b]
    command: python3 merge.py
```

**Execution:**
```
    start
    ┌─┴─┐
branch_a branch_b
    └─┬─┘
     join
```

### Complex DAG

```yaml
name: complex_workflow
tasks:
  - id: init
    command: mkdir -p /tmp/workflow
  
  - id: fetch_api_data
    depends_on: [init]
    command: curl https://api.example.com/data > /tmp/workflow/api.json
  
  - id: fetch_db_data
    depends_on: [init]
    command: psql -c "COPY data TO '/tmp/workflow/db.csv'"
  
  - id: process_api
    depends_on: [fetch_api_data]
    command: python3 transform_api.py
  
  - id: process_db
    depends_on: [fetch_db_data]
    command: python3 transform_db.py
  
  - id: merge_data
    depends_on: [process_api, process_db]
    command: python3 merge.py
  
  - id: generate_report
    depends_on: [merge_data]
    command: python3 report.py
  
  - id: send_email
    depends_on: [generate_report]
    command: python3 send_email.py
```

---

## Retry & Timeout Configuration

### Retry Strategy

**Default Behavior:**
- Failed tasks retry up to `max_retries` times
- Exponential backoff between retries (future enhancement)
- Workflow fails if task exceeds max retries

**Example:**

```yaml
tasks:
  - id: flaky_api_call
    name: Call External API
    command: curl -f https://api.example.com/data
    max_retries: 5          # Retry 5 times on failure
    timeout_seconds: 30     # Each attempt times out after 30s
```

**Retry Scenarios:**

```yaml
# No retries (fail immediately)
- id: critical_task
  command: ./deploy.sh
  max_retries: 0

# Many retries (for flaky operations)
- id: network_call
  command: wget https://unreliable-server.com/file
  max_retries: 10

# Default retries (3 attempts)
- id: normal_task
  command: python3 script.py
  # max_retries defaults to 3
```

### Timeout Configuration

**Task-Level Timeout:**

```yaml
- id: long_running_task
  command: python3 ml_training.py
  timeout_seconds: 7200  # 2 hours max
```

**Short Timeout for Quick Tasks:**

```yaml
- id: health_check
  command: curl -f http://service:8080/health
  timeout_seconds: 10    # Fail if takes > 10 seconds
```

**No Timeout (Not Recommended):**

```yaml
- id: indefinite_task
  command: python3 stream_processor.py
  # No timeout_seconds = runs forever until completion or error
```

---

## Advanced Patterns

### Environment Variables

Use environment variables in commands:

```yaml
tasks:
  - id: deploy_to_env
    command: |
      export ENVIRONMENT=${ENVIRONMENT:-staging}
      ./deploy.sh --env $ENVIRONMENT
```

**Set variables in worker configuration:**
```bash
# In worker-service environment
ENVIRONMENT=production
API_KEY=secret-key-123
```

### Multi-Line Commands

**Using pipe operator:**

```yaml
- id: complex_processing
  command: |
    cd /tmp/data
    for file in *.csv; do
      python3 process.py "$file"
    done
    echo "Processing complete"
```

**Using semicolons:**

```yaml
- id: quick_commands
  command: mkdir -p /tmp/output; cd /tmp/output; wget https://example.com/data.zip; unzip data.zip
```

### Conditional Execution (Future)

**Roadmap v1.1:**

```yaml
- id: deploy_to_prod
  command: ./deploy.sh production
  depends_on: [run_tests]
  when: "${run_tests.exit_code} == 0 && ${BRANCH} == 'main'"
```

### Dynamic Workflows (Future)

**Roadmap v1.2:**

```yaml
- id: process_files
  command: python3 process.py --file ${file}
  for_each: ${files}  # Loop over array
```

---

## Best Practices

### 1. Idempotency

Make tasks idempotent (can run multiple times safely):

❌ **Bad:**
```yaml
- id: append_data
  command: echo "new data" >> /tmp/file.txt
  # Running twice duplicates data
```

✅ **Good:**
```yaml
- id: write_data
  command: echo "new data" > /tmp/file.txt
  # Running twice produces same result
```

### 2. Task Granularity

Split large tasks into smaller, focused tasks:

❌ **Bad:**
```yaml
- id: do_everything
  command: |
    download_data.sh
    process_data.py
    upload_results.sh
    send_email.py
  # Hard to debug, no parallelism, retry all-or-nothing
```

✅ **Good:**
```yaml
- id: download
  command: download_data.sh

- id: process
  depends_on: [download]
  command: process_data.py

- id: upload
  depends_on: [process]
  command: upload_results.sh

- id: notify
  depends_on: [upload]
  command: send_email.py
```

### 3. Error Handling

Check exit codes and handle errors:

```yaml
- id: safe_download
  command: |
    wget https://example.com/data.zip || {
      echo "Download failed"
      exit 1
    }
```

### 4. Resource Cleanup

Add cleanup tasks:

```yaml
- id: process_data
  command: python3 process.py

- id: cleanup
  depends_on: [process_data]
  command: rm -rf /tmp/workflow-data
  max_retries: 1  # Always try to cleanup
```

### 5. Meaningful IDs and Names

```yaml
# Bad
- id: t1
  name: Task

# Good
- id: extract_user_data
  name: Extract User Data from PostgreSQL
```

### 6. Set Appropriate Timeouts

```yaml
# Quick health check
- id: health_check
  command: curl http://service/health
  timeout_seconds: 10

# Long training job
- id: ml_training
  command: python3 train_model.py
  timeout_seconds: 14400  # 4 hours
```

### 7. Use Absolute Paths

```yaml
# Bad
- id: run_script
  command: python script.py

# Good
- id: run_script
  command: python3 /opt/scripts/process.py
```

---

## Validation & Debugging

### Validate Workflow Syntax

**Manual Validation:**

```bash
# Check YAML syntax
yamllint workflow.yaml

# Test with small workflow first
curl -X POST http://localhost:8080/api/workflows/start \
  --data-binary @test-workflow.yaml
```

**Common Errors:**

1. **Circular Dependencies**
```yaml
# ERROR: Creates cycle
- id: task_a
  depends_on: [task_b]

- id: task_b
  depends_on: [task_a]
```

2. **Invalid Task ID Reference**
```yaml
- id: task_2
  depends_on: [task_1]  # ERROR: task_1 doesn't exist
```

3. **Duplicate Task IDs**
```yaml
- id: process_data
  command: echo "First"

- id: process_data  # ERROR: Duplicate ID
  command: echo "Second"
```

### Debugging Failed Workflows

**1. Check Worker Logs:**
```bash
docker logs job-scheduler-worker-1 --tail 100
```

**2. Check API Logs:**
```bash
docker logs job-scheduler-api --tail 100
```

**3. Verify Task Command Locally:**
```bash
# Test command on worker machine
docker exec -it job-scheduler-worker-1 bash
# Run the command manually
echo "Hello World"
```

**4. Check Task Status:**
```bash
curl http://localhost:8080/api/workflows/{workflowRunId}
# Look for failed tasks
```

---

## Examples

### Example 1: Daily ETL Pipeline

```yaml
name: daily_etl_pipeline
description: Extract, transform, and load daily sales data

tasks:
  - id: extract_from_database
    name: Extract Sales Data
    command: |
      psql -U user -d salesdb \
        -c "COPY (SELECT * FROM sales WHERE date = CURRENT_DATE) TO '/tmp/sales.csv' CSV HEADER"
    timeout_seconds: 300
    max_retries: 2

  - id: transform_data
    name: Transform Sales Data
    depends_on: [extract_from_database]
    command: python3 /opt/etl/transform_sales.py --input /tmp/sales.csv --output /tmp/sales_clean.csv
    timeout_seconds: 600
    max_retries: 2

  - id: load_to_warehouse
    name: Load to Data Warehouse
    depends_on: [transform_data]
    command: python3 /opt/etl/load_snowflake.py --file /tmp/sales_clean.csv
    timeout_seconds: 900
    max_retries: 3

  - id: send_notification
    name: Send Success Notification
    depends_on: [load_to_warehouse]
    command: |
      curl -X POST https://hooks.slack.com/services/YOUR_WEBHOOK \
        -H 'Content-Type: application/json' \
        -d '{"text":"✅ Daily ETL completed successfully"}'
    max_retries: 3
```

### Example 2: Parallel Image Processing

```yaml
name: image_processing_pipeline
description: Process images in parallel batches

tasks:
  - id: download_images
    name: Download Images from S3
    command: aws s3 sync s3://images/pending /tmp/images
    max_retries: 3

  - id: process_batch_1
    name: Process Images 1-1000
    depends_on: [download_images]
    command: python3 /opt/process_images.py --start 1 --end 1000
    timeout_seconds: 3600

  - id: process_batch_2
    name: Process Images 1001-2000
    depends_on: [download_images]
    command: python3 /opt/process_images.py --start 1001 --end 2000
    timeout_seconds: 3600

  - id: process_batch_3
    name: Process Images 2001-3000
    depends_on: [download_images]
    command: python3 /opt/process_images.py --start 2001 --end 3000
    timeout_seconds: 3600

  - id: upload_results
    name: Upload Processed Images
    depends_on: [process_batch_1, process_batch_2, process_batch_3]
    command: aws s3 sync /tmp/processed s3://images/processed
    max_retries: 5
```

### Example 3: CI/CD Deployment

```yaml
name: production_deployment
description: Deploy application to production with validation

tasks:
  - id: run_unit_tests
    name: Run Unit Tests
    command: cd /app && npm test
    timeout_seconds: 300

  - id: build_docker_image
    name: Build Docker Image
    depends_on: [run_unit_tests]
    command: docker build -t myapp:${VERSION} .
    timeout_seconds: 600

  - id: push_to_registry
    name: Push to Container Registry
    depends_on: [build_docker_image]
    command: docker push myapp:${VERSION}
    max_retries: 3

  - id: deploy_to_staging
    name: Deploy to Staging
    depends_on: [push_to_registry]
    command: kubectl set image deployment/myapp myapp=myapp:${VERSION} -n staging

  - id: smoke_test_staging
    name: Smoke Test Staging
    depends_on: [deploy_to_staging]
    command: curl -f https://staging.myapp.com/health
    max_retries: 5
    timeout_seconds: 60

  - id: deploy_to_production
    name: Deploy to Production
    depends_on: [smoke_test_staging]
    command: kubectl set image deployment/myapp myapp=myapp:${VERSION} -n production

  - id: notify_team
    name: Notify Team
    depends_on: [deploy_to_production]
    command: |
      curl -X POST https://hooks.slack.com/services/YOUR_WEBHOOK \
        -d '{"text":"🚀 Deployed version ${VERSION} to production"}'
```

---

## Next Steps

- See [USE_CASES.md](USE_CASES.md) for more real-world examples
- Check [API_TESTING_GUIDE.md](API_TESTING_GUIDE.md) for API usage
- Explore [workflow-samples/](api-service/src/main/resources/workflow-samples/) for templates

---

**Happy workflow building! 🚀**

