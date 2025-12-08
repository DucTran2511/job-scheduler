# Spring Boot Actuator - Built-in Health Checks

## What's Already Included (No Code Needed!)

Spring Boot Actuator **automatically** provides health checks for:

### 1. **Database Health** ✅ AUTOMATIC
- **Auto-configured when**: You have a `DataSource` bean
- **What it checks**: Database connectivity, can execute queries
- **Endpoint component**: `db`
- **No code needed!**

**Already working because you have:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
```

### 2. **Redis Health** ✅ AUTOMATIC
- **Auto-configured when**: You have a `RedisConnectionFactory` bean
- **What it checks**: Redis connectivity via PING command
- **Endpoint component**: `redis`
- **No code needed!**

**Already working because you have:**
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

### 3. **Disk Space Health** ✅ AUTOMATIC
- **Auto-configured**: Always
- **What it checks**: Available disk space
- **Endpoint component**: `diskSpace`
- **No code needed!**

### 4. **Ping Health** ✅ AUTOMATIC
- **Auto-configured**: Always
- **What it checks**: Application is running
- **Endpoint component**: `ping`
- **No code needed!**

---

## What You Get Automatically

When you call `/actuator/health`, you automatically get:

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
        "exists": true
      }
    },
    "ping": {
      "status": "UP"
    },
    "redis": {
      "status": "UP",
      "details": {
        "version": "7.0.0"
      }
    }
  }
}
```

**All of this WITHOUT writing any code!**

---

## When Should You Write Custom Health Indicators?

Only write custom health indicators for **business-specific** health checks:

### ✅ Good Reasons to Write Custom Health Indicators:
1. **Application-specific logic** (e.g., workflow orchestrator status)
2. **External service health** (e.g., third-party API availability)
3. **Business metrics** (e.g., queue size, pending jobs)
4. **Custom resource checks** (e.g., file system access, specific directory)
5. **Circuit breaker status** (e.g., whether external calls are failing)

### ❌ DON'T Write Custom Health Indicators For:
1. ❌ Database connectivity (built-in)
2. ❌ Redis connectivity (built-in)
3. ❌ Disk space (built-in)
4. ❌ MongoDB connectivity (built-in)
5. ❌ Cassandra connectivity (built-in)
6. ❌ RabbitMQ connectivity (built-in)
7. ❌ Kafka connectivity (built-in)

---

## Built-in Health Indicators (Complete List)

Spring Boot provides these out-of-the-box:

| Component | Auto-configured When | What It Checks |
|-----------|---------------------|----------------|
| `db` | DataSource bean exists | Database connectivity |
| `redis` | RedisConnectionFactory exists | Redis PING |
| `mongo` | MongoClient exists | MongoDB connectivity |
| `cassandra` | CassandraDriver exists | Cassandra connectivity |
| `elasticsearch` | ElasticsearchClient exists | Elasticsearch cluster health |
| `rabbitmq` | RabbitTemplate exists | RabbitMQ connection |
| `kafka` | KafkaTemplate exists | Kafka broker connectivity |
| `solr` | SolrClient exists | Solr ping |
| `diskSpace` | Always | Disk space availability |
| `ping` | Always | Application is running |
| `livenessState` | When probes enabled | Kubernetes liveness |
| `readinessState` | When probes enabled | Kubernetes readiness |
| `mail` | JavaMailSender exists | Mail server connectivity |
| `jms` | JmsTemplate exists | JMS broker connectivity |
| `ldap` | LdapTemplate exists | LDAP connectivity |
| `hazelcast` | HazelcastInstance exists | Hazelcast cluster |

---

## Example: What You Should NOT Do

### ❌ BAD - Redundant Redis Health Check
```java
@Component
public class RedisStreamHealthIndicator implements HealthIndicator {
    private final RedisTemplate<String, Object> redisTemplate;
    
    @Override
    public Health health() {
        try {
            // DON'T DO THIS - Already built-in!
            String result = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();
            
            return Health.up().withDetail("redis", "Connected").build();
        } catch (Exception e) {
            return Health.down().build();
        }
    }
}
```

**Why it's bad:** Spring Boot already provides `redis` health check!

---

## Example: What You SHOULD Do

### ✅ GOOD - Business-Specific Health Check
```java
@Component
@RequiredArgsConstructor
public class WorkflowOrchestratorHealthIndicator implements HealthIndicator {
    private final WorkflowRunRepository workflowRunRepository;
    
    @Override
    public Health health() {
        try {
            // Check application-specific logic
            long totalWorkflows = workflowRunRepository.count();
            
            return Health.up()
                    .withDetail("orchestrator", "Operational")
                    .withDetail("totalWorkflows", totalWorkflows)
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("orchestrator", "Database query failed")
                    .build();
        }
    }
}
```

**Why it's good:** This checks YOUR business logic, not infrastructure!

---

## How Built-in Health Checks Work

### 1. Database Health (`DataSourceHealthIndicator`)
**Spring Boot's code** (you don't write this):
```java
@Component
public class DataSourceHealthIndicator implements HealthIndicator {
    private final DataSource dataSource;
    
    @Override
    public Health health() {
        try (Connection conn = dataSource.getConnection()) {
            // Validates connection
            if (conn.isValid(2)) {
                return Health.up()
                    .withDetail("database", getDatabaseProductName(conn))
                    .build();
            }
        } catch (SQLException e) {
            return Health.down(e).build();
        }
    }
}
```

### 2. Redis Health (`RedisHealthIndicator`)
**Spring Boot's code** (you don't write this):
```java
@Component
public class RedisHealthIndicator implements HealthIndicator {
    private final RedisConnectionFactory connectionFactory;
    
    @Override
    public Health health() {
        try {
            RedisConnection connection = connectionFactory.getConnection();
            String pong = connection.ping();
            
            return Health.up()
                .withDetail("version", connection.info().getProperty("redis_version"))
                .build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
```

**You get all of this for FREE!**

---

## Configuration Options

### Show/Hide Health Details
```properties
# Show details only when authorized (default)
management.endpoint.health.show-details=when-authorized

# Always show details (dev/testing)
management.endpoint.health.show-details=always

# Never show details (production - less info leakage)
management.endpoint.health.show-details=never
```

### Disable Specific Health Indicators
```properties
# Disable disk space check
management.health.diskspace.enabled=false

# Disable Redis health check
management.health.redis.enabled=false

# Disable database health check
management.health.db.enabled=false
```

### Customize Health Check Behavior
```properties
# Database health check timeout
spring.datasource.hikari.connection-timeout=2000

# Disk space threshold
management.health.diskspace.threshold=10MB

# Redis health check timeout
spring.data.redis.timeout=2000ms
```

---

## Recommended Approach for Your Project

### Keep Only Business-Specific Health Checks

**For api-service, you should have:**
```
api-service/src/main/java/com/api/health/
└── WorkflowOrchestratorHealthIndicator.java  ← Keep this (business logic)
```

**DELETE or don't create:**
- ❌ RedisStreamHealthIndicator (redundant - Redis health is built-in)
- ❌ DatabaseHealthIndicator (redundant - DB health is built-in)

**For worker-service:**
```
worker-service/src/main/java/com/worker/health/
└── WorkerHealthIndicator.java  ← Keep simple worker status
```

---

## Testing Built-in Health Checks

### 1. Start services
```bash
docker-compose up postgres redis -d
cd api-service && ./mvnw spring-boot:run
```

### 2. Check health
```bash
curl http://localhost:8080/actuator/health | jq .
```

### 3. You'll see automatic health checks:
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
    "redis": {
      "status": "UP",
      "details": {
        "version": "7.2.3"
      }
    },
    "diskSpace": {
      "status": "UP"
    },
    "ping": {
      "status": "UP"
    },
    "workflowOrchestrator": {
      "status": "UP",
      "details": {
        "orchestrator": "Operational",
        "totalWorkflows": 0
      }
    }
  }
}
```

### 4. Test failure scenarios

**Stop PostgreSQL:**
```bash
docker-compose stop postgres
curl http://localhost:8080/actuator/health
# "db": { "status": "DOWN" }
```

**Stop Redis:**
```bash
docker-compose stop redis
curl http://localhost:8080/actuator/health
# "redis": { "status": "DOWN" }
```

---

## Summary

| Health Check | Need to Write Code? | Provided By |
|--------------|---------------------|-------------|
| Database | ❌ No | Spring Boot Actuator (automatic) |
| Redis | ❌ No | Spring Boot Actuator (automatic) |
| Disk Space | ❌ No | Spring Boot Actuator (automatic) |
| Ping | ❌ No | Spring Boot Actuator (automatic) |
| Liveness/Readiness | ❌ No | Spring Boot Actuator (automatic) |
| Workflow Status | ✅ Yes | You (business logic) |
| Worker Status | ✅ Yes | You (business logic) |

---

## Key Takeaways

1. **DON'T write health indicators for infrastructure** (DB, Redis, etc.) - they're built-in!
2. **DO write health indicators for business logic** (workflow status, custom checks)
3. **Spring Boot auto-configures** health checks based on your dependencies
4. **Just add the dependency** and configure with properties - that's it!
5. **Save time and avoid redundancy** - use what's already there!

---

## Further Reading

- [Spring Boot Actuator Documentation](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.endpoints.health)
- [Built-in Health Indicators](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.endpoints.health.auto-configured-health-indicators)
- [Writing Custom Health Indicators](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.endpoints.health.writing-custom-health-indicators)

