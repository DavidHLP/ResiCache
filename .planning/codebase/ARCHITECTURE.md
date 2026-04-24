# Architecture

**Analysis Date:** 2026-04-24

## Pattern Overview

**Overall:** Chain of Responsibility Pattern with Spring Cache Abstraction

**Key Characteristics:**
- Extends Spring's caching abstraction with Redis as the underlying store
- Uses AOP-based annotation processing for cache operations
- Implements a configurable handler chain for cross-cutting cache concerns
- Provides protection mechanisms against common cache failure modes (penetration, breakdown, avalanche)

## Layers

**Annotation Layer:**
- Purpose: Define cache operations via annotations
- Location: `src/main/java/io/github/davidhlp/spring/cache/redis/annotation/`
- Contains: `@RedisCacheable`, `@RedisCacheEvict`, `@RedisCachePut`, `@RedisCaching`
- Depends on: Spring AOP, Spring Cache
- Used by: Application code via method annotations

**Interceptor Layer:**
- Purpose: Intercept method invocations and route to cache handlers
- Location: `src/main/java/io/github/davidhlp/spring/cache/redis/core/RedisCacheInterceptor.java`
- Contains: `RedisCacheInterceptor` (extends `CacheInterceptor`)
- Depends on: `AnnotationHandler` implementations
- Used by: Spring Cache infrastructure

**Handler Layer:**
- Purpose: Process different annotation types and build cache operations
- Location: `src/main/java/io/github/davidhlp/spring/cache/redis/core/handler/`
- Contains: `CacheableAnnotationHandler`, `EvictAnnotationHandler`, `CachePutAnnotationHandler`, `CachingAnnotationHandler`
- Depends on: `RedisCacheRegister`, operation factories
- Used by: `RedisCacheInterceptor`

**Operation Layer:**
- Purpose: Represent and store cache operation metadata
- Location: `src/main/java/io/github/davidhlp/spring/cache/redis/register/operation/`
- Contains: `RedisCacheableOperation`, `RedisCacheEvictOperation`, `RedisCachePutOperation`
- Depends on: Spring Cache `CacheOperation`
- Used by: `RedisCacheRegister`, handler chain

**Registry Layer:**
- Purpose: Register and retrieve cache operations by cache/key
- Location: `src/main/java/io/github/davidhlp/spring/cache/redis/register/RedisCacheRegister.java`
- Contains: `RedisCacheRegister`
- Depends on: `EvictionStrategy` for managing operation storage
- Used by: `RedisCacheInterceptor`, `RedisProCacheWriter`

**Writer Layer:**
- Purpose: Execute actual cache operations with protection mechanisms
- Location: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/`
- Contains: `RedisProCacheWriter`, `CachedValue`
- Depends on: Redis template, handler chain, SPI providers
- Used by: `RedisProCache`

**Chain Layer:**
- Purpose: Process cache operations through configurable handler chain
- Location: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/`
- Contains: `CacheHandlerChain`, `CacheHandler`, `CacheContext`, `CacheResult`
- Depends on: Individual handlers
- Used by: `RedisProCacheWriter`

**Handler Implementations:**
- Purpose: Implement specific cache protection/optimization behaviors
- Location: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/`
- Contains: `BloomFilterHandler`, `SyncLockHandler`, `PreRefreshHandler`, `TtlHandler`, `NullValueHandler`, `ActualCacheHandler`
- Depends on: Support classes for each feature
- Used by: `CacheHandlerChain`

**Support Layer:**
- Purpose: Provide infrastructure for handlers (bloom filter, locking, TTL, refresh)
- Location: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/support/`
- Contains: `BloomSupport`, `SyncSupport`, `TtlPolicy`, `PreRefreshSupport`, `NullValuePolicy`
- Depends on: SPI providers, Redis operations
- Used by: Handler implementations

**SPI Layer:**
- Purpose: Pluggable providers for BloomFilter and Lock implementations
- Location: `src/main/java/io/github/davidhlp/spring/cache/redis/spi/`
- Contains: `BloomFilter`, `BloomFilterProvider`, `LockProvider`, `LockManager`, `RedissonLockProvider`
- Depends on: Redisson (for default lock implementation)
- Used by: Support layer

**Configuration Layer:**
- Purpose: Spring Boot auto-configuration and property binding
- Location: `src/main/java/io/github/davidhlp/spring/cache/redis/config/`
- Contains: `RedisCacheAutoConfiguration`, `RedisProCacheConfiguration`, `RedisProCacheProperties`, `RedisConnectionConfiguration`
- Depends on: Spring Boot autoconfiguration
- Used by: Spring Boot application context

**Cache Layer:**
- Purpose: Custom cache implementation extending RedisCache with metrics
- Location: `src/main/java/io/github/davidhlp/spring/cache/redis/core/RedisProCache.java`
- Contains: `RedisProCache` (extends `RedisCache`)
- Depends on: `RedisProCacheWriter`, Micrometer
- Used by: `RedisProCacheManager`

**Manager Layer:**
- Purpose: Create and manage cache instances
- Location: `src/main/java/io/github/davidhlp/spring/cache/redis/manager/RedisProCacheManager.java`
- Contains: `RedisProCacheManager` (extends `RedisCacheManager`)
- Depends on: `RedisProCacheWriter`, Redis cache configuration
- Used by: Spring Cache abstraction

**Strategy Layer:**
- Purpose: Eviction strategy for managing operation metadata
- Location: `src/main/java/io/github/davidhlp/spring/cache/redis/strategy/eviction/`
- Contains: `EvictionStrategy` (interface), `EvictionStrategyFactory`, `TwoListEvictionStrategy`
- Depends on: None (pure Java implementation)
- Used by: `RedisCacheRegister`

**Evaluator Layer:**
- Purpose: SpEL expression evaluation for condition/unless
- Location: `src/main/java/io/github/davidhlp/spring/cache/redis/core/evaluator/SpelConditionEvaluator.java`
- Contains: `SpelConditionEvaluator`
- Depends on: Spring SpEL
- Used by: `RedisCacheInterceptor`

## Data Flow

**Cacheable Operation Flow:**

1. Method annotated with `@RedisCacheable` is invoked
2. `RedisCacheInterceptor.invoke()` is called by AOP
3. `handleCacheAnnotations()` uses handler chain to process annotations
4. `CacheableAnnotationHandler` builds `RedisCacheableOperation` and registers it via `RedisCacheRegister`
5. `evaluateCondition()` checks SpEL condition expressions
6. If condition passes, `super.invoke()` proceeds to target method
7. On method return, result goes through the handler chain to `RedisProCacheWriter`
8. `RedisProCacheWriter` builds `CacheContext` and executes handler chain
9. Chain: `BloomFilterHandler` (check) -> `SyncLockHandler` (lock if needed) -> `PreRefreshHandler` (check) -> `TtlHandler` (calculate) -> `NullValueHandler` (process) -> `ActualCacheHandler` (Redis op)
10. Result propagates back through chain and returns to caller

**Cache Evict Operation Flow:**

1. Method annotated with `@RedisCacheEvict` is invoked
2. `RedisCacheInterceptor` processes via `EvictAnnotationHandler`
3. Registers `RedisCacheEvictOperation` in `RedisCacheRegister`
4. `ActualCacheHandler` executes Redis DELETE operation
5. `BloomFilterHandler.postProcess()` may clear bloom filter

**Handler Chain Order (via `@HandlerPriority`):**

| Order | Handler | Purpose |
|-------|---------|---------|
| 100 | `BloomFilterHandler` | Cache penetration prevention |
| 200 | `SyncLockHandler` | Cache breakdown prevention |
| 250 | `PreRefreshHandler` | Hot key protection |
| 300 | `TtlHandler` | TTL calculation with randomization |
| 400 | `NullValueHandler` | Null value caching |
| 500 | `ActualCacheHandler` | Actual Redis operations |

## Key Abstractions

**CacheHandler (Chain of Responsibility):**
- Purpose: Process cache operations in chain
- Examples: `BloomFilterHandler`, `SyncLockHandler`, `ActualCacheHandler`
- Pattern: Each handler has `shouldHandle()`, `doHandle()`, `setNext()`, `getNext()`
- Supports post-processing via `PostProcessHandler` interface

**CacheOperation (Operation Types):**
- Purpose: Enum-based operation types
- Examples: `GET`, `PUT`, `PUT_IF_ABSENT`, `REMOVE`, `CLEAN`
- Used by: `CacheContext` to determine routing

**HandlerResult (Decision Pattern):**
- Purpose: Encapsulate handler decision and result
- Contains: `decision` (CONTINUE/TERMINATE/SKIP_ALL), `result`
- Controls chain flow

**CachedValue (Value Wrapper):**
- Purpose: Wrap cached values with metadata
- Contains: value, ttl, createdTime, startNanoTime, lastAccessTime, visitTimes, version
- Uses monotonic clock (`System.nanoTime()`) for reliable TTL calculation

**EvictionStrategy (LRU Management):**
- Purpose: Manage operation metadata with bounded memory
- Examples: `TwoListEvictionStrategy` (active/inactive lists)
- Provides: `put()`, `get()`, `remove()`, `size()`, `getStats()`

## Entry Points

**Spring Boot Auto-Configuration:**
- Location: `src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisCacheAutoConfiguration.java`
- Triggers: `spring-boot-autoconfigure` detects Redis on classpath
- Responsibilities: Import all configuration classes, ensure correct load order

**Annotation Scanning:**
- Enabled via: `@ComponentScan(basePackages = "io.github.davidhlp.spring.cache.redis")`
- Location: `RedisProCacheConfiguration`

**User Annotation Usage:**
```java
@RedisCacheable(value = "users", key = "#id", sync = true, ttl = 300)
public User findUser(Long id) { ... }

@RedisCacheEvict(value = "users", key = "#id")
public void updateUser(Long id, User user) { ... }
```

## Error Handling

**Strategy:** Delegating error handler per operation type

**Patterns:**
- `CacheErrorHandler` interface with methods for each operation type
- Returns `CacheResult` with error information instead of throwing
- Errors logged but do not break cache operations
- Statistics updated on errors (incHits/incMisses continue)

**Key Methods:**
- `handleGetError()`
- `handlePutError()`
- `handlePutIfAbsentError()`
- `handleRemoveError()`
- `handleCleanError()`

## Cross-Cutting Concerns

**Logging:** Slf4j (`@Slf4j` from Lombok) - debug/info for operations, warn for errors

**Metrics:** Micrometer via `MeterRegistry` and `CacheStatisticsCollector`
- `resicache.cache.get` - Timer for get operations
- `resicache.cache.put` - Timer for put operations
- `resicache.cache.evict` - Timer for evict operations

**Configuration:** `@ConfigurationProperties` with `resi-cache` prefix
- `defaultTtl`
- `bloomFilter.*`
- `preRefresh.*`
- `syncLock.*`
- `disabledHandlers`

**Thread Safety:**
- Handler chain is cached (double-checked locking)
- AtomicLong counters in `RedisProCache` for hit/miss tracking
- `CachedValue` uses `startNanoTime` for thread-safe TTL

---

*Architecture analysis: 2026-04-24*
