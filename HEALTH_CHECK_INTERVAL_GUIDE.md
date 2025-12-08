# Health Check Interval Configuration Guide

## Important: Health Check Intervals Are NOT Configured in Java Code!

Your Java code (`WorkflowOrchestratorHealthIndicator`) **only defines WHAT to check**.

The **infrastructure** (Kubernetes, Docker, load balancers) **configures WHEN to check** (every 5-30 seconds).

---

## 1. Docker Compose Health Checks

**File: `docker-compose.yaml`**

```yaml
version: '3.8'

services:
  api-service:
    build:
      context: ./api-service
      dockerfile: Dockerfile
    ports:
      - "8080:8080"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 10s          # ← Check every 10 seconds
      timeout: 5s            # ← Fail if no response in 5 seconds
      retries: 3             # ← Restart after 3 consecutive failures
      start_period: 40s      # ← Wait 40s before first check (app startup time)
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy

  worker-service:
    build:
      context: ./worker-service
      dockerfile: Dockerfile
    ports:
      - "8081:8081"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8081/actuator/health"]
      interval: 15s          # ← Check every 15 seconds
      timeout: 5s
      retries: 3
      start_period: 40s
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy

  postgres:
    image: postgres:16
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U jobuser -d jobdb"]
      interval: 10s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 3s
      retries: 5
```

**Test it:**
```bash
# Start services
docker-compose up -d

# Watch health status in real-time
watch -n 1 'docker-compose ps'

# You'll see health checks running every 10-15 seconds
# Status will show: "starting" → "healthy" or "unhealthy"
```

---

## 2. Kubernetes Health Checks

**File: `k8s/api-service-deployment.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: api-service
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
        image: job-scheduler/api-service:latest
        ports:
        - containerPort: 8080
        
        # Liveness Probe - Restart pod if fails
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 30    # Wait 30s after pod starts
          periodSeconds: 10          # ← Check every 10 seconds
          timeoutSeconds: 3          # Fail if no response in 3s
          successThreshold: 1        # 1 success = healthy
          failureThreshold: 3        # 3 failures = restart pod
        
        # Readiness Probe - Remove from service if fails
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 20    # Wait 20s after pod starts
          periodSeconds: 5           # ← Check every 5 seconds (more frequent)
          timeoutSeconds: 3
          successThreshold: 1
          failureThreshold: 3        # 3 failures = stop routing traffic
        
        # Startup Probe - For slow-starting apps
        startupProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 0
          periodSeconds: 5           # ← Check every 5 seconds during startup
          timeoutSeconds: 3
          failureThreshold: 30       # Allow 150s (30 * 5s) for startup
```

**File: `k8s/worker-service-deployment.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: worker-service
spec:
  replicas: 5    # Scale workers horizontally
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
        image: job-scheduler/worker-service:latest
        ports:
        - containerPort: 8081
        
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8081
          initialDelaySeconds: 30
          periodSeconds: 15          # ← Check every 15 seconds
          timeoutSeconds: 5
          failureThreshold: 3
        
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8081
          initialDelaySeconds: 20
          periodSeconds: 10          # ← Check every 10 seconds
          timeoutSeconds: 5
          failureThreshold: 2
```

**Deploy and monitor:**
```bash
# Apply deployments
kubectl apply -f k8s/

# Watch health checks in real-time
kubectl get pods -w

# Check pod health details
kubectl describe pod <pod-name>

# View probe failures
kubectl get events --sort-by='.lastTimestamp'
```

---

## 3. AWS Elastic Load Balancer

**File: `terraform/alb.tf` or AWS Console Configuration**

```hcl
resource "aws_lb_target_group" "api_service" {
  name     = "api-service-tg"
  port     = 8080
  protocol = "HTTP"
  vpc_id   = aws_vpc.main.id

  health_check {
    enabled             = true
    path                = "/actuator/health"
    port                = "8080"
    protocol            = "HTTP"
    interval            = 30              # ← Check every 30 seconds
    timeout             = 5               # Timeout after 5 seconds
    healthy_threshold   = 2               # 2 success = healthy
    unhealthy_threshold = 3               # 3 failures = unhealthy
    matcher             = "200"           # HTTP 200 = success
  }
}

resource "aws_lb_target_group" "worker_service" {
  name     = "worker-service-tg"
  port     = 8081
  protocol = "HTTP"
  vpc_id   = aws_vpc.main.id

  health_check {
    enabled             = true
    path                = "/actuator/health"
    interval            = 30              # ← Check every 30 seconds
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
    matcher             = "200"
  }
}
```

---

## 4. Prometheus Monitoring

**File: `prometheus/prometheus.yml`**

```yaml
global:
  scrape_interval: 15s        # ← Scrape metrics every 15 seconds
  evaluation_interval: 15s    # Evaluate rules every 15 seconds

scrape_configs:
  # Job Scheduler API Service
  - job_name: 'api-service'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 10s      # ← Override: check every 10 seconds
    static_configs:
      - targets: ['api-service:8080']
        labels:
          service: 'api-service'
          environment: 'production'

  # Job Scheduler Worker Service
  - job_name: 'worker-service'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 15s      # ← Check every 15 seconds
    static_configs:
      - targets: 
        - 'worker-service-1:8081'
        - 'worker-service-2:8081'
        - 'worker-service-3:8081'
        labels:
          service: 'worker-service'
          environment: 'production'
```

**File: `prometheus/alert-rules.yml`**

```yaml
groups:
  - name: job-scheduler-health
    interval: 30s             # ← Evaluate alerts every 30 seconds
    rules:
      # Alert if service is down
      - alert: ServiceDown
        expr: up{job=~"api-service|worker-service"} == 0
        for: 1m               # Alert after 1 minute of being down
        labels:
          severity: critical
        annotations:
          summary: "Service {{ $labels.job }} is down"

      # Alert if workflows are stuck
      - alert: WorkflowsStuck
        expr: workflow_orchestrator_stuck_workflows > 5
        for: 5m               # Alert after 5 minutes
        labels:
          severity: warning
        annotations:
          summary: "{{ $value }} workflows stuck for over 1 hour"

      # Alert if worker is idle
      - alert: WorkerIdle
        expr: worker_tasks_processed_total == 0
        for: 10m              # Alert after 10 minutes of no activity
        labels:
          severity: warning
        annotations:
          summary: "Worker {{ $labels.instance }} has processed no tasks"
```

---

## 5. NGINX Load Balancer

**File: `nginx/nginx.conf`**

```nginx
http {
    upstream api_backend {
        server api-service-1:8080 max_fails=3 fail_timeout=30s;
        server api-service-2:8080 max_fails=3 fail_timeout=30s;
        server api-service-3:8080 max_fails=3 fail_timeout=30s;
        
        # Health check (NGINX Plus only)
        # For open-source NGINX, use external health check script
    }

    upstream worker_backend {
        server worker-service-1:8081 max_fails=3 fail_timeout=30s;
        server worker-service-2:8081 max_fails=3 fail_timeout=30s;
    }

    server {
        listen 80;

        location / {
            proxy_pass http://api_backend;
            proxy_next_upstream error timeout http_502 http_503 http_504;
        }

        # Health check endpoint
        location /health {
            access_log off;
            proxy_pass http://api_backend/actuator/health;
        }
    }
}
```

**External health check script (for NGINX open-source):**

**File: `nginx/health-check.sh`**

```bash
#!/bin/bash

# Check health every 10 seconds
INTERVAL=10

while true; do
    # Check API service
    for port in 8080 8081 8082; do
        if ! curl -f -s http://localhost:$port/actuator/health > /dev/null; then
            echo "$(date): API service on port $port is DOWN"
            # Mark backend as down in NGINX (requires nginx-upstream-check module)
        fi
    done
    
    # Check worker service
    for port in 9080 9081; do
        if ! curl -f -s http://localhost:$port/actuator/health > /dev/null; then
            echo "$(date): Worker service on port $port is DOWN"
        fi
    done
    
    sleep $INTERVAL
done
```

---

## 6. Spring Boot Actuator Default Behavior

**Your application.properties** (already configured):

```properties
# Actuator endpoints
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=when-authorized
management.endpoint.health.probes.enabled=true
management.health.livenessState.enabled=true
management.health.readinessState.enabled=true

# Note: These properties DON'T control health check frequency!
# They only control WHAT information is exposed.
# The calling system (K8s, Docker, LB) controls WHEN it's checked.
```

**What happens when health endpoint is called:**
1. Kubernetes/Docker/LB sends HTTP GET request: `http://api-service:8080/actuator/health`
2. Spring Boot receives request
3. Executes `WorkflowOrchestratorHealthIndicator.health()` (your code)
4. Returns JSON response
5. Caller evaluates response and decides if service is healthy

**Response time:** Your health check should complete in **< 1 second** to avoid timeouts!

---

## 7. Comparison: Different Intervals for Different Use Cases

| Use Case | Interval | Why |
|----------|----------|-----|
| **Kubernetes Liveness** | 10-15s | Balance between fast failure detection and avoiding CPU overhead |
| **Kubernetes Readiness** | 5-10s | More frequent to quickly remove unhealthy pods from traffic |
| **Kubernetes Startup** | 5s | Frequent checks during startup, then switches to liveness |
| **Docker Compose** | 10-30s | Less critical, mainly for local dev |
| **AWS ALB** | 30s | Default AWS recommendation, cost-effective |
| **GCP Load Balancer** | 5-10s | GCP default for fast detection |
| **Prometheus Scraping** | 10-15s | Balance between metric resolution and overhead |
| **Alert Evaluation** | 30-60s | Less frequent to avoid alert fatigue |
| **Manual Testing** | On-demand | `curl` when needed |

---

## 8. Performance Considerations

### Your Health Check Should Be Fast!

```java
// ✅ GOOD - Fast health check (< 100ms)
public Health health() {
    boolean canPublish = redisPublisher != null;  // Fast
    long activeWorkflows = repository.countByStatus(RUNNING);  // Fast query
    return Health.up().build();
}

// ❌ BAD - Slow health check (> 1 second)
public Health health() {
    List<WorkflowRun> all = repository.findAll();  // Loads ALL workflows!
    all.stream()...  // Heavy computation
    return Health.up().build();
}
```

**Why it matters:**
- If interval = 5s and health check takes 2s → 40% CPU usage just for health checks!
- If health check times out → False positive failures → Unnecessary restarts

**Your current implementation is GOOD:**
- `redisPublisher != null` → Fast (< 1ms)
- `redisDagStore != null` → Fast (< 1ms)
- `countByStatus(RUNNING)` → Fast indexed query (< 50ms)
- `findAll().stream()...` → Could be slow if many workflows, consider optimizing

---

## 9. Testing Health Check Intervals

### Test with Docker Compose:

```bash
# Start with 5-second interval
docker-compose up -d

# Watch health checks in real-time
docker events --filter 'event=health_status'

# You'll see:
# 2025-11-22T10:00:00 api-service: health_status: healthy
# 2025-11-22T10:00:05 api-service: health_status: healthy
# 2025-11-22T10:00:10 api-service: health_status: healthy

# Simulate failure (stop Redis)
docker-compose stop redis

# Watch it detect failure within 5-10 seconds
# 2025-11-22T10:00:15 api-service: health_status: unhealthy
```

### Test with Kubernetes:

```bash
# Deploy
kubectl apply -f k8s/

# Watch probe results
kubectl get events -w | grep -i probe

# You'll see:
# Liveness probe succeeded (every 10s)
# Readiness probe succeeded (every 5s)

# Simulate failure
kubectl exec -it api-service-pod -- pkill java

# Watch Kubernetes detect and restart
# Liveness probe failed (after 3 failures = 30s)
# Pod restarting...
```

---

## 10. Recommended Configuration for Your Job Scheduler

**For Production:**

```yaml
# docker-compose.prod.yaml
services:
  api-service:
    healthcheck:
      interval: 10s      # ← Recommended for production
      timeout: 3s
      retries: 3
      start_period: 40s

  worker-service:
    healthcheck:
      interval: 15s      # ← Workers can have longer interval
      timeout: 5s
      retries: 3
      start_period: 40s
```

**For Development:**

```yaml
# docker-compose.yaml (local dev)
services:
  api-service:
    healthcheck:
      interval: 30s      # ← Less frequent for dev (save CPU)
      timeout: 5s
      retries: 3
      start_period: 40s
```

---

## Summary

### Key Points:

1. **Health check interval is NOT in Java code** - it's infrastructure configuration
2. **Different systems, different intervals:**
   - Kubernetes: 5-15 seconds
   - Docker: 10-30 seconds
   - Load balancers: 30 seconds
   - Monitoring: 15-30 seconds
3. **Your Java code must be FAST** - complete in < 1 second
4. **Configure intervals based on:**
   - How quickly you need to detect failures
   - CPU/network overhead acceptable
   - Cost (AWS charges for health checks)

### What You Configure Where:

| Where | What |
|-------|------|
| **Java code** | WHAT to check (your health logic) |
| **docker-compose.yaml** | WHEN to check (interval: 10s) |
| **Kubernetes YAML** | WHEN to check (periodSeconds: 5) |
| **AWS/GCP console** | WHEN to check (interval: 30s) |
| **Prometheus config** | WHEN to scrape (scrape_interval: 15s) |

You configure the interval in the **infrastructure layer**, not in your application code!

