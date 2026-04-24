# ResiCache Actuator Endpoints

ResiCache exposes several Actuator endpoints for monitoring cache performance, bloom filter status, and health.

## Endpoints Overview

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/actuator/resicache/cache/{cacheName}/stats` | GET | Cache statistics (hits, misses, hit rate) |
| `/actuator/resicache/cache/{cacheName}/bloom-filter` | GET | Bloom filter status and configuration |
| `/actuator/resicache/health` | GET | ResiCache health indicator |

## Enable Actuator Endpoints

### 1. Add Actuator Dependency

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

### 2. Configure Endpoint Exposure

```yaml
# application.yml
spring:
  application:
    name: my-application
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

management:
  endpoints:
    web:
      exposure:
        include: health,info,resicache
  endpoint:
    resicache:
      enabled: true
    health:
      show-details: always
  endpoint-health:
    show-details: always
```

## Endpoint Details

### GET /actuator/resicache/cache/{cacheName}/stats

Returns cache statistics for the specified cache.

**Response Fields:**

| Field | Type | Description |
|-------|------|-------------|
| `cache.hits` | long | Total number of cache hits |
| `cache.misses` | long | Total number of cache misses |
| `cache.hitRate` | double | Cache hit rate (0.0 to 1.0) |

**Example Response:**

```json
{
  "cacheName": "users",
  "cache": {
    "hits": 1523,
    "misses": 87,
    "hitRate": 0.946
  }
}
```

**curl Example:**

```bash
# Get cache statistics for 'users' cache
curl -s http://localhost:8080/actuator/resicache/cache/users/stats | jq .
```

### GET /actuator/resicache/cache/{cacheName}/bloom-filter

Returns bloom filter configuration and status.

**Response Fields:**

| Field | Type | Description |
|-------|------|-------------|
| `bloomFilter.enabled` | boolean | Whether bloom filter is enabled |
| `bloomFilter.expectedInsertions` | long | Expected number of insertions |
| `bloomFilter.falseProbability` | double | Expected false positive probability |

**Example Response:**

```json
{
  "cacheName": "users",
  "bloomFilter": {
    "enabled": true,
    "expectedInsertions": 100000,
    "falseProbability": 0.01
  }
}
```

**curl Example:**

```bash
# Get bloom filter status for 'users' cache
curl -s http://localhost:8080/actuator/resicache/cache/users/bloom-filter | jq .
```

### GET /actuator/resicache/health

Returns the health status of ResiCache components.

**Response Fields:**

| Field | Type | Description |
|-------|------|-------------|
| `status` | string | Overall health status (UP, DOWN) |
| `components.redis.status` | string | Redis connection status |
| `components.bloomFilter.status` | string | Bloom filter status |
| `components.preRefresh.status` | string | Pre-refresh executor status |

**Example Response:**

```json
{
  "status": "UP",
  "components": {
    "redis": {
      "status": "UP",
      "details": {
        "version": "7.0.0"
      }
    },
    "bloomFilter": {
      "status": "UP",
      "details": {
        "enabled": true,
        "poolSize": 2
      }
    },
    "preRefresh": {
      "status": "UP",
      "details": {
        "enabled": true,
        "activeThreads": 1
      }
    }
  }
}
```

**curl Example:**

```bash
# Get overall ResiCache health
curl -s http://localhost:8080/actuator/resicache/health | jq .
```

## Pre-refresh Endpoint (Additional)

### GET /actuator/resicator/cache/{cacheName}/pre-refresh

Returns pre-refresh configuration and status.

**Response Fields:**

| Field | Type | Description |
|-------|------|-------------|
| `preRefresh.enabled` | boolean | Whether pre-refresh is enabled |
| `preRefresh.poolSize` | int | Current pool size |
| `preRefresh.activeCount` | int | Number of active threads |

**Example Response:**

```json
{
  "cacheName": "users",
  "preRefresh": {
    "enabled": true,
    "poolSize": 4,
    "activeCount": 2,
    "queueCapacity": 100
  }
}
```

## Complete Spring Boot 3.x Configuration Example

```yaml
# application.yml
spring:
  application:
    name: resicache-demo
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      timeout: 5000ms

resi-cache:
  default-ttl: 30m
  serializer:
    allowed-package-prefixes:
      - io.github.davidhlp
      - com.example.business
  bloom-filter:
    enabled: true
    expected-insertions: 100000
    false-probability: 0.01
  pre-refresh:
    enabled: true
    core-pool-size: 4
    max-pool-size: 10
    queue-capacity: 100
  lock:
    enabled: true
    wait-time: 5
    lease-time: 30

management:
  endpoints:
    web:
      exposure:
        include: health,info,resicache
  endpoint:
    resicache:
      enabled: true
    health:
      show-details: always
```

## Security Considerations

- Actuator endpoints may expose sensitive operational data
- In production, restrict endpoint access using Spring Security
- Consider network-level restrictions for actuator endpoints

```java
@Configuration
@Profile("production")
public class ActuatorSecurityConfig {

    @Bean
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .requestMatcher(EndpointRequest.to("resicache", "health"))
            .authorizeHttpRequests(auth -> auth
                .hasRole("ADMIN")
            )
            .requestMatcher(EndpointRequest.toAnyEndpoint())
            .authorizeHttpRequests(auth -> auth
                .hasRole("ACTUATOR_ADMIN")
            );
        return http.build();
    }
}
```
