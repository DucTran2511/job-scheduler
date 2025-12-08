# Spring Boot Actuator Health Check Guide

## Overview
Spring Boot Actuator provides production-ready features including health checks, metrics, and monitoring endpoints.

## Available Endpoints

### API Service (Port 8080)
- **Health Check**: `http://localhost:8080/actuator/health`
- **Detailed Health**: `http://localhost:8080/actuator/health` (with authorization)
- **Liveness Probe**: `http://localhost:8080/actuator/health/liveness`
- **Readiness Probe**: `http://localhost:8080/actuator/health/readiness`
- **Metrics**: `http://localhost:8080/actuator/metrics`
- **Prometheus Metrics**: `http://localhost:8080/actuator/prometheus`
- **Info**: `http://localhost:8080/actuator/info`

### Worker Service (Port 8081)
- **Health Check**: `http://localhost:8081/actuator/health`
- **Detailed Health**: `http://localhost:8081/actuator/health` (with authorization)
- **Liveness Probe**: `http://localhost:8081/actuator/health/liveness`
- **Readiness Probe**: `http://localhost:8081/actuator/health/readiness`
- **Metrics**: `http://localhost:8081/actuator/metrics`
- **Prometheus Metrics**: `http://localhost:8081/actuator/prometheus`
- **Info**: `http://localhost:8081/actuator/info`

## Custom Health Indicators

### API Service
1. **WorkflowOrchestratorHealthIndicator** - Checks workflow system health
   - Database connectivity
   - Total workflows count
   
2. **RedisStreamHealthIndicator** - Checks Redis connectivity
   - Redis ping test
   - Stream availability

### Worker Service
1. **WorkerHealthIndicator** - Checks worker service readiness
   - Service operational status
   
2. **RedisStreamHealthIndicator** - Checks Redis connectivity
   - Redis ping test
   - Task consumption readiness

## How to Test

### Method 1: Using curl (Terminal)

**Basic health check:**
```bash
curl http://localhost:8080/actuator/health
```

**Liveness check (for Kubernetes):**
```bash
curl http://localhost:8080/actuator/health/liveness
```

**Readiness check (for Kubernetes):**
```bash
curl http://localhost:8080/actuator/health/readiness
```

**Pretty print with jq:**
```bash
curl -s http://localhost:8080/actuator/health | jq .
```

**Check specific component:**
```bash
curl http://localhost:8080/actuator/health/db
curl http://localhost:8080/actuator/health/redis
```

### Method 2: Using Browser
Simply navigate to:
- http://localhost:8080/actuator/health
- http://localhost:8081/actuator/health

### Method 3: Using HTTPie
```bash
http :8080/actuator/health
http :8081/actuator/health
```

### Method 4: Using Postman
1. Create GET request
2. URL: `http://localhost:8080/actuator/health`
3. Send request

## Expected Responses

### Healthy Service
```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "validationQuery": "isValid()"
      }
    },
    "diskSpace": {
      "status": "UP",
      "details": {
        "total": 500000000000,
        "free": 250000000000,
        "threshold": 10485760,
        "path": "/path/to/app",
        "exists": true
      }
    },
    "ping": {
      "status": "UP"
    },
    "redis": {
      "status": "UP",
      "details": {
        "version": "7.x.x"
      }
    },
    "redisStream": {
      "status": "UP",
      "details": {
        "redis": "Connected",
        "status": "Streams available"
      }
    },
    "workflowOrchestrator": {
      "status": "UP",
      "details": {
        "orchestrator": "Operational",
        "totalWorkflows": 42
      }
    }
  }
}
```

### Unhealthy Service
```json
{
  "status": "DOWN",
  "components": {
    "db": {
      "status": "DOWN",
      "details": {
        "error": "Connection refused"
      }
    },
    "redisStream": {
      "status": "DOWN",
      "details": {
        "redis": "Connection failed",
        "error": "Connection refused"
      }
    }
  }
}
```

### Liveness Probe Response
```json
{
  "status": "UP"
}
```

### Readiness Probe Response
```json
{
  "status": "UP"
}
```

## Testing Scenarios

### 1. Test with All Services Running
```bash
# Start PostgreSQL and Redis
docker-compose up postgres redis -d

# Start API service
cd api-service
./mvnw spring-boot:run

# In another terminal, check health
curl http://localhost:8080/actuator/health
```

### 2. Test with Database Down
```bash
# Stop PostgreSQL
docker-compose stop postgres

# Check health (should show DOWN)
curl http://localhost:8080/actuator/health
```

### 3. Test with Redis Down
```bash
# Stop Redis
docker-compose stop redis

# Check health (should show DOWN for Redis components)
curl http://localhost:8080/actuator/health
```

### 4. Test in Docker
```bash
# Start all services
docker-compose up -d

# Wait for services to be healthy
docker-compose ps

# Check health from inside container
docker exec job_api_service curl http://localhost:8080/actuator/health

# Check health from host
curl http://localhost:8080/actuator/health
```

## Integration with Monitoring

### Prometheus
Actuator exposes Prometheus-compatible metrics at `/actuator/prometheus`:
```bash
curl http://localhost:8080/actuator/prometheus
```

### Docker Health Checks
In docker-compose.yaml:
```yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
  interval: 30s
  timeout: 10s
  retries: 3
  start_period: 40s
```

### Kubernetes Probes
```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 20
  periodSeconds: 5
```

## Metrics Available

Get list of all metrics:
```bash
curl http://localhost:8080/actuator/metrics
```

Get specific metric:
```bash
curl http://localhost:8080/actuator/metrics/jvm.memory.used
curl http://localhost:8080/actuator/metrics/http.server.requests
curl http://localhost:8080/actuator/metrics/system.cpu.usage
```

## Security Considerations

For production, you should:
1. Secure actuator endpoints with Spring Security
2. Use different management port
3. Limit exposed endpoints
4. Configure detailed health info only for authorized users

Example configuration for production:
```properties
# Use separate port for management
management.server.port=9090

# Restrict endpoints
management.endpoints.web.exposure.include=health,info,metrics

# Hide details from public
management.endpoint.health.show-details=when-authorized

# Require authentication
spring.security.user.name=admin
spring.security.user.password=${ACTUATOR_PASSWORD}
```

## Troubleshooting

### Health endpoint returns 404
- Check if `spring-boot-starter-actuator` dependency is added
- Verify `management.endpoints.web.exposure.include` includes `health`

### Health shows DOWN but service works
- Check custom health indicators for errors
- Review application logs for exceptions
- Verify database/Redis connections

### Health check times out
- Increase health check timeout in docker-compose
- Check if database queries in health indicators are slow
- Review network connectivity

## Quick Test Script

Save as `test-health.sh`:
```bash
#!/bin/bash

echo "=== Testing API Service Health ==="
curl -s http://localhost:8080/actuator/health | jq .

echo -e "\n=== Testing Worker Service Health ==="
curl -s http://localhost:8081/actuator/health | jq .

echo -e "\n=== Testing Liveness Probes ==="
curl -s http://localhost:8080/actuator/health/liveness | jq .
curl -s http://localhost:8081/actuator/health/liveness | jq .

echo -e "\n=== Testing Readiness Probes ==="
curl -s http://localhost:8080/actuator/health/readiness | jq .
curl -s http://localhost:8081/actuator/health/readiness | jq .

echo -e "\n=== Done ==="
```

Make it executable and run:
```bash
chmod +x test-health.sh
./test-health.sh
```

