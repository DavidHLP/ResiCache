# External Integrations

**Analysis Date:** 2026-04-24

## APIs & External Services

### Redis (Primary Data Store)

**Type:** NoSQL Database / Cache
- **Purpose:** Distributed caching layer for Spring Cache abstraction
- **SDK/Client:** Redisson 3.27.0 via `redisson-spring-boot-starter`
- **Additional Client:** Spring Data Redis (`spring-boot-starter-data-redis`)
- **Connection Details:**
  - Configured via `RedisConnectionConfiguration`
  - Uses `RedisProperties` from Spring Data Redis
  - Supports standard Spring Boot properties:
    - `spring.data.redis.host` (default: localhost)
    - `spring.data.redis.port` (default: 6379)
    - `spring.data.redis.password`
    - `spring.data.redis.database` (default: 0)
  - Redisson single server mode configured with:
    - Connection pool size: 64
    - Minimum idle connections: 10
    - Idle connection timeout: 10000ms
    - Connect timeout: 10000ms
    - Operation timeout: 3000ms
    - Retry attempts: 3
    - Retry interval: 1500ms

**Files Involved:**
- `src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisConnectionConfiguration.java` - Redis connection setup
- `src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProCacheProperties.java` - Configuration properties
- `src/main/java/io/github/davidhlp/spring/cache/redis/core/RedisProCache.java` - Cache implementation
- `src/main/java/io/github/davidhlp/spring/cache/redis/manager/RedisProCacheManager.java` - Cache manager

### Spring Cache Abstraction

**Type:** Caching Framework
- **Purpose:** Standard Spring caching interface extended with custom Redis implementation
- **Integration:** Custom annotations (`@RedisCacheable`, `@RedisCacheEvict`, `@RedisCachePut`, `@RedisCaching`) built on top of Spring Cache

## Data Storage

### Redis (Primary Cache Store)

**Type:** In-Memory Data Store / Cache
- **Connection:** Configured through Spring Data Redis `RedisConnectionFactory`
- **Client:** `RedisTemplate<String, Object>` with:
  - Keys serialized via `StringRedisSerializer`
  - Values serialized via `GenericJackson2JsonRedisSerializer`
- **Cache Regions:** Managed by `RedisProCacheManager`
- **Eviction Strategies:** Configurable via `EvictionStrategy` and `EvictionStrategyFactory`

**File Storage:** Not applicable (Redis only)

**Caching:** Built-in via Spring Cache + Redis

## SPI Providers (Pluggable Architecture)

### BloomFilterProvider SPI

**Type:** Service Provider Interface
- **Purpose:** Pluggable bloom filter implementation for cache bloom filtering
- **Interface:** `io.github.davidhlp.spring.cache.redis.spi.BloomFilterProvider`
- **Default Implementation:** None registered (SPI file is empty/commented)
- **SPI Registration:**
  - File: `META-INF/services/io.github.davidhlp.spring.cache.redis.spi.BloomFilterProvider`
  - Users can register custom implementations via Java SPI mechanism
- **Configuration:** `resi-cache.bloom-filter.provider` property

### LockProvider SPI

**Type:** Service Provider Interface
- **Purpose:** Pluggable distributed lock implementation
- **Interface:** `io.github.davidhlp.spring.cache.redis.spi.LockProvider`
- **Default Implementation:** `RedissonLockProvider` (uses Redisson)
- **SPI Registration:**
  - File: `META-INF/services/io.github.davidhlp.spring.cache.redis.spi.LockProvider`
- **Configuration:** `resi-cache.sync-lock.provider` property

## Authentication & Identity

**Auth Provider:** Not applicable
- This is a library/framework, not a user-facing application
- No authentication implementation within the codebase

## Monitoring & Observability

### Spring Boot Actuator

**Type:** Application Monitoring
- **Dependency:** `spring-boot-starter-actuator` (optional)
- **Purpose:** Health checks, metrics, and application monitoring endpoints
- **Integration:** Standard Spring Boot Actuator auto-configuration

**Error Tracking:** Not implemented
- No custom error tracking service integrated
- Relies on standard Spring Boot error handling

**Logs:**
- SLF4J with Lombok `@Slf4j` annotation
- Logback as the underlying logging implementation (Spring Boot default)
- Log levels configurable via standard Spring Boot logging properties

## CI/CD & Deployment

**Hosting:** Maven Central (distribution via Sonatype)
- **Publishing Plugin:** `central-publishing-maven-plugin` 0.8.0
- **Repository:** Maven Central
- **GPG Signing:** Required for release (`maven-gpg-plugin` 3.1.0)

**CI Pipeline:** Not detected
- No CI configuration files in repository (`.github/workflows` not present)
- Standard Maven build process

**Artifact:**
- `ResiCache-0.0.2.jar` (released version)
- Source JAR (`-sources.jar`)
- Javadoc JAR (`-javadoc.jar`)

## Environment Configuration

**Configuration Class:** `RedisProCacheProperties`

Prefix: `resi-cache`

### Top-Level Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `default-ttl` | Duration | 30m | Default cache TTL |
| `disabled-handlers` | List<String> | [] | Globally disabled handlers |
| `handler-settings` | Map | {} | Per-cache-name handler config |

### Bloom Filter Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `bloom-filter.enabled` | boolean | true | Enable bloom filter |
| `bloom-filter.expected-insertions` | long | 100000 | Expected insert count |
| `bloom-filter.false-probability` | double | 0.01 | Expected false positive rate |

### Pre-Refresh Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `pre-refresh.enabled` | boolean | true | Enable cache pre-refresh |
| `pre-refresh.pool-size` | int | 2 | Core thread pool size |
| `pre-refresh.max-pool-size` | int | 10 | Max thread pool size |
| `pre-refresh.queue-capacity` | int | 100 | Queue capacity |

### Sync Lock Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `sync-lock.timeout` | long | 3000 | Lock timeout |
| `sync-lock.unit` | TimeUnit | MILLISECONDS | Timeout unit |

### Test Configuration

Located: `src/test/resources/application-test.yml`

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password: ""
      database: 0

resi-cache:
  default-ttl: 30m
  bloom-filter:
    enabled: false
  pre-refresh:
    enabled: false
  sync-lock:
    enabled: false
  disabled-handlers:
    - bloom-filter
    - pre-refresh
    - sync-lock
```

## Key Configuration Entry Points

**Auto-Configuration Class:**
- `RedisCacheAutoConfiguration` - Main entry point
- Registered via: `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Imports:
  - `JacksonConfig` - JSON serialization configuration
  - `RedisConnectionConfiguration` - Redis connection setup
  - `RedisCacheRegistryConfiguration` - Cache registry setup
  - `RedisProxyCachingConfiguration` - AOP proxy configuration
  - `RedisProCacheConfiguration` - Cache implementation configuration

## Package Structure (Integration Points)

```
io.github.davidhlp.spring.cache.redis/
├── annotation/          # Custom cache annotations
├── config/             # Spring configurations
├── core/               # Core cache implementation
├── manager/            # Cache managers
├── register/           # Cache operation registration
├── spi/                # Service provider interfaces
├── strategy/           # Eviction strategies
├── core/
│   ├── evaluator/      # SpEL condition evaluation
│   ├── factory/        # Operation factories
│   ├── handler/        # Annotation handlers
│   └── writer/         # Cache writers
```

---

*Integration audit: 2026-04-24*
