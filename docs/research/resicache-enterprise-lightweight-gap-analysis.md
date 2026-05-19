# ResiCache Enterprise Lightweight Gap Analysis

Generated: 2026-05-19

## Executive Summary

ResiCache has a useful direction: a Spring Redis cache enhancement library with a handler chain for TTL jitter, null-value caching, Bloom filters, distributed locking, pre-refresh, metrics, and cache operation metadata. However, as an "enterprise-grade and lightweight Spring Redis Cache plugin", the project is not yet ready. The main blockers are not missing edge features, but core Spring Cache contract mismatches and incomplete end-to-end behavior.

The highest-risk issue is that custom cache operations inherit `CacheOperation` instead of Spring's concrete operation types such as `CacheableOperation`, `CachePutOperation`, and `CacheEvictOperation`. Spring's cache aspect routes behavior by concrete operation class, so custom operations can be parsed but not necessarily executed with the expected cacheable/put/evict semantics.

## Reference Baseline

The analysis uses Spring's cache abstraction and Spring Data Redis cache behavior as the baseline.

- Spring Cache supports `@Cacheable`, `@CachePut`, `@CacheEvict`, `@Caching`, custom composed annotations, `key`, `keyGenerator`, `cacheManager`, `cacheResolver`, `condition`, `unless`, and `sync`.
- Spring Data Redis `RedisCacheManager` supports TTL, null-value handling, serialization, per-cache configuration, transaction-aware behavior, TTI, key prefixes, and locking/non-locking cache writers.

Sources:

- Spring Cache annotations: https://docs.spring.io/spring-framework/reference/6.2/integration/cache/annotations.html
- Spring Data Redis cache reference: https://github.com/spring-projects/spring-data-redis/blob/main/src/main/antora/modules/ROOT/pages/redis/redis-cache.adoc

## Critical Defects

### 1. Custom Operations Do Not Match Spring Cache Concrete Operation Types

File: `src/main/java/io/github/davidhlp/spring/cache/redis/annotation/RedisCacheOperationSource.java`

`RedisCacheOperationSource` returns custom operation objects such as `RedisCacheableOperation`, `RedisCacheEvictOperation`, and `RedisCachePutOperation`. These classes extend `org.springframework.cache.interceptor.CacheOperation`, not Spring's concrete `CacheableOperation`, `CachePutOperation`, or `CacheEvictOperation`.

Spring's cache aspect internally groups and executes operations by concrete operation class. This means custom operations can be discovered, but may not enter the standard cacheable, put, or evict execution paths. This is a P0 correctness issue.

Recommended fix:

- Either use Spring's concrete operation classes and store ResiCache-specific metadata separately, or
- Stop extending Spring's standard interceptor and implement the full custom annotation execution model directly.

### 2. Annotation Attributes Are Declared But Not Parsed Into Operations

File: `src/main/java/io/github/davidhlp/spring/cache/redis/annotation/RedisCacheOperationSource.java`

`@RedisCacheable` declares enterprise features:

- `ttl`
- `type`
- `cacheNullValues`
- `useBloomFilter`
- `randomTtl`
- `variance`
- `enablePreRefresh`
- `preRefreshThreshold`
- `preRefreshMode`

However, `parseRedisCacheable` only transfers cache names, key, condition, unless, sync, keyGenerator, and cacheManager. The same pattern appears in `parseRedisCachePut`.

Impact:

- README-advertised features may not work through the real Spring Cache invocation path.
- Handler chain features depend on `context.getCacheOperation()`, so missing metadata silently disables them.

Recommended fix:

- Add end-to-end tests for each annotation attribute.
- Ensure every annotation field is represented in runtime metadata used by `RedisProCacheWriter`.

### 3. `unless` Semantics Are Incorrect

File: `src/main/java/io/github/davidhlp/spring/cache/redis/core/RedisCacheInterceptor.java`

The interceptor calls `super.invoke(invocation)` first, then evaluates `unless`. If `unless` is true, it only logs a warning that the value was already cached.

Spring's contract is that `unless` is evaluated after method execution but before writing the result to cache. Current behavior violates user expectations and can cache data that the annotation explicitly says not to cache.

Recommended fix:

- Let Spring's native operation model handle `unless`, or
- Rework interception so `unless` is evaluated before cache write.

### 4. Registered Metadata Key Does Not Match Actual Spring Cache Key

File: `src/main/java/io/github/davidhlp/spring/cache/redis/core/handler/CacheableAnnotationHandler.java`

The handler registers operations using the global `KeyGenerator` result:

```java
Object key = keyGenerator.generate(target, method, args);
```

It does not evaluate the annotation's SpEL `key` expression. Spring Cache may use a different key for the actual Redis operation, especially with:

- `key = "#id"`
- custom SpEL expressions
- custom `keyGenerator`
- multiple arguments
- class-level annotations

Impact:

- `RedisCacheRegister.getCacheableOperation(cacheName, actualKey)` may not find the matching metadata.
- Bloom filter, sync lock, TTL jitter, null caching, and pre-refresh may be bypassed.

Recommended fix:

- Reuse Spring's `CacheOperationExpressionEvaluator` path or another equivalent key resolution mechanism.
- Store metadata by operation identity, not only by generated Redis key.

### 5. `@RedisCachePut` Is Parsed As Cacheable

File: `src/main/java/io/github/davidhlp/spring/cache/redis/annotation/RedisCacheOperationSource.java`

`parseRedisCachePut` creates a `RedisCacheableOperation` instead of a `RedisCachePutOperation`.

Impact:

- `@RedisCachePut` may not provide the expected "always execute method and update cache" behavior.
- It can behave like a cacheable operation or fail to participate in Spring's native put path.

Recommended fix:

- Create a true put operation model and verify it through service-level integration tests.

## Enterprise Gaps

### Configuration Model Is Too Thin

Spring Data Redis supports mature cache configuration options such as per-cache TTL, null value behavior, serialization, key prefixes, transaction-aware behavior, TTI, and batch strategies. ResiCache currently exposes a narrower global configuration model.

Missing or incomplete areas:

- Per-cache TTL and feature toggles
- Serializer configuration and migration strategy
- Key prefix strategy
- Transaction-aware cache manager support
- TTI support
- Batch strategy for cache-wide eviction
- Clear error policy configuration

### Redis Deployment Support Is Incomplete

File: `src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisConnectionConfiguration.java`

Redisson is configured as single-server only using host, port, database, and password.

Missing enterprise deployment modes:

- Redis Cluster
- Redis Sentinel
- TLS
- username/ACL support
- read mode and subscription mode tuning
- cloud Redis endpoint nuances
- user-supplied Redisson config passthrough

### Redisson Dependency Is Too Heavy For A Lightweight Plugin

File: `pom.xml`

`redisson-spring-boot-starter` is a required dependency. For a lightweight cache plugin, distributed locking should be optional.

Recommended fix:

- Make Redisson optional.
- Provide a no-op/local lock fallback or SPI-only mode.
- Auto-enable distributed lock support only when a `RedissonClient` bean exists.

### Serializer Configuration Is Not Production-Ready

File: `src/main/java/io/github/davidhlp/spring/cache/redis/config/SecureJackson2JsonRedisSerializer.java`

The serializer documentation mentions `resi-cache.serializer.allowed-package-prefixes`, but `RedisProCacheProperties` does not expose that property and `RedisProCacheConfiguration` always constructs the serializer with the default package prefix.

There is also a security concern: the type validator includes `allowIfSubType(Object.class)`, which may weaken the intended package whitelist.

Recommended fix:

- Add serializer properties to `RedisProCacheProperties`.
- Remove or justify broad subtype allowance.
- Add compatibility tests for domain object packages.
- Document migration behavior for existing cached values.

### Pre-Refresh Is Not Real Background Refresh

File: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PreRefreshHandler.java`

Async pre-refresh shortens TTL but does not call the original loader to refresh data in the background. This is closer to "early expiry" than "pre-refresh".

Recommended fix:

- Rename the feature to early expiration, or
- Capture a safe refresh callback and execute true background reload with single-flight protection.

### Metrics Are Inaccurate

File: `src/main/java/io/github/davidhlp/spring/cache/redis/core/RedisProCache.java`

Problems:

- `get(Object key, Callable<T> loader)` is instrumented, but plain `get(Object key)` is not.
- `size` is not incremented on put and only decremented on evict.
- Metrics can diverge from Redis reality and should not be used for capacity or precise hit-rate decisions.

Recommended fix:

- Instrument all cache access paths.
- Prefer Spring Data Redis `CacheStatisticsCollector` and Micrometer meters over local counters.
- Avoid exposing approximate size as exact size.

## Testing And Quality Gaps

Command run:

```bash
./mvnw test -q
```

Result:

- Exit code: 0
- JaCoCo line coverage: 73.62%
- JaCoCo branch coverage: 45.82%
- JaCoCo instruction coverage: 64.85%

Notes:

- The repository instruction target is 80%+ coverage, but the current Maven threshold is 60%.
- Test logs show Testcontainers could not find a valid Docker environment because the Docker client API was too old, yet the test run still passed. This suggests Redis integration verification can silently lose strength in local or CI environments.

Recommended fix:

- Raise JaCoCo line coverage to at least 80%.
- Add a meaningful branch coverage threshold.
- Mark Docker-dependent tests as explicitly skipped when Docker is unavailable.
- Add end-to-end tests that call annotated service methods and assert Redis values, TTL, null handling, Bloom behavior, lock behavior, and pre-refresh behavior.

## Documentation Gaps

The README is useful but currently overstates maturity relative to implementation.

Missing documentation:

- Compatibility matrix for Spring Boot, Spring Data Redis, Java, Redis, Redisson
- Native Spring Cache feature compatibility table
- Known limitations
- Production configuration examples
- Serializer whitelist configuration
- Cluster/Sentinel/TLS deployment guidance
- Observability and metric names
- Failure-mode behavior when Redis is unavailable
- Migration guide and semantic versioning policy

## Prioritized Remediation Plan

### P0: Correctness

1. Decide whether ResiCache is a Spring Cache extension or a fully custom cache annotation framework.
2. Fix operation type modeling.
3. Fix `@RedisCachePut` and `@RedisCacheEvict` execution semantics.
4. Fix `unless` behavior.
5. Fix key resolution and metadata lookup.
6. Add service-level integration tests for the above.

### P1: Feature Contract

1. Ensure every annotation attribute is parsed and used.
2. Add per-cache configuration.
3. Make Redisson optional.
4. Add serializer properties and tests.
5. Clarify pre-refresh semantics.

### P2: Enterprise Readiness

1. Support Cluster, Sentinel, TLS, and username.
2. Add transaction-aware support.
3. Improve metrics accuracy.
4. Add failure-mode and degradation policy configuration.
5. Add production documentation and compatibility matrix.

### P3: Lightweight Polish

1. Reduce required starters and hard dependencies.
2. Prefer conditional auto-configuration.
3. Split optional modules if needed:
   - `resicache-core`
   - `resicache-redisson-lock`
   - `resicache-bloom`
   - `resicache-actuator`

## Final Assessment

Current status: experimental Spring Redis Cache enhancement library.

Target status: enterprise-grade lightweight Spring Redis Cache plugin.

Main gap: Spring Cache contract correctness. The project should stop adding new features until the core annotation and cache operation model is correct end to end.
