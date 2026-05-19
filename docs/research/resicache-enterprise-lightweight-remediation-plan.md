# ResiCache Enterprise Lightweight Remediation Plan

Generated: 2026-05-19

## Summary

This plan turns the gap analysis in
`docs/research/resicache-enterprise-lightweight-gap-analysis.md` into an
execution roadmap.

Goal: move ResiCache from an experimental Redis cache enhancement library to a
Spring Cache compatible, verifiable, and lightweight Spring Redis Cache plugin.

Default technical direction: ResiCache remains a Spring Cache extension. It
should not become a separate custom cache AOP framework. Custom annotations must
enter Spring Cache's standard execution path.

Execution order:

1. Fix P0 correctness.
2. Complete the feature contract.
3. Add enterprise configuration and runtime behavior.
4. Finish lightweight dependency shaping and documentation.

Detailed implementation plan:
`.claude/PRPs/plans/resicache-enterprise-remediation.plan.md`

## Phase 0: Protective Test Baseline

- Add annotation-level integration test services that call real proxied methods
  annotated with `@RedisCacheable`, `@RedisCachePut`, `@RedisCacheEvict`, and
  `@RedisCaching`.
- Verify actual Redis values, TTL, null-value behavior, Bloom metadata, sync
  lock behavior, and pre-refresh metadata.
- Make Docker/Redis-dependent tests explicitly skipped when Redis is
  unavailable. They must not silently pass with reduced coverage.
- Raise JaCoCo line coverage to at least 80% and add a meaningful branch
  coverage threshold, starting at 60%.
- Keep current unit tests, but use them as lower-level guards instead of
  treating them as proof that the Spring Cache invocation path works.

## Phase 1: Spring Cache Core Semantics

- Keep ResiCache as a Spring Cache extension.
- Change custom operation classes to extend Spring's concrete operation types:
  - `RedisCacheableOperation extends CacheableOperation`
  - `RedisCachePutOperation extends CachePutOperation`
  - `RedisCacheEvictOperation extends CacheEvictOperation`
- Update `RedisCacheOperationSource` so all custom annotations produce operation
  objects that Spring's `CacheAspectSupport` can route through the native
  cacheable, put, and evict paths.
- Fix `parseRedisCachePut` so it creates a real `RedisCachePutOperation`, not a
  cacheable operation.
- Preserve Spring semantics:
  - `condition=false` skips cache behavior before method execution.
  - `unless=true` runs the method but prevents cache write.
  - `@RedisCachePut` always executes the method and writes the result.
  - `@RedisCacheEvict` honors `allEntries` and `beforeInvocation`.
- Remove cache-control behavior from `RedisCacheInterceptor` that manually
  evaluates `condition` and `unless` around `super.invoke()`.
- Reduce `RedisCacheInterceptor` to metadata registration, delegation to
  `super.invoke()`, and cleanup of any ResiCache invocation context.
- Rework key resolution so metadata lookup matches Spring's actual cache key.
  Do not rely only on the global `KeyGenerator`.
- Prefer metadata binding by operation identity and invocation context. Use
  `cacheName + resolvedKey` only as a fallback lookup strategy.

## Phase 2: Annotation Attribute Contract

- Parse every field declared by `@RedisCacheable`, `@RedisCachePut`, and
  `@RedisCacheEvict`.
- Required cacheable/put metadata:
  - `ttl`
  - `type`
  - `cacheNullValues`
  - `useBloomFilter`
  - `randomTtl`
  - `variance`
  - `enablePreRefresh`
  - `preRefreshThreshold`
  - `preRefreshMode`
  - `sync`
  - `syncTimeout`
  - `cacheManager`
  - `cacheResolver`
  - `keyGenerator`
  - `condition`
  - `unless`
- Ensure `RedisCacheRegister` stores the complete runtime metadata used by
  `RedisProCacheWriter` and the handler chain.
- Make `TtlHandler` use annotation TTL and random TTL settings. Fall back to
  per-cache configuration, then global default TTL.
- Make `NullValueHandler` use operation-level `cacheNullValues`.
- Make `BloomFilterHandler` use operation-level `useBloomFilter`; default it to
  disabled unless explicitly enabled by annotation or configuration.
- Make `SyncLockHandler` use `sync` and `syncTimeout`. If no distributed lock
  provider exists, fall back to local single-flight behavior.
- Implement true pre-refresh: when a hit is close to expiry, trigger one
  background reload through the original loader and write the refreshed value
  back to Redis.
- If no safe loader callback is available, skip pre-refresh and log at debug
  level. Do not pretend TTL shortening is a background refresh.

## Phase 3: Enterprise Configuration

- Extend `RedisProCacheProperties` with per-cache settings:
  - `resi-cache.caches.<cacheName>.ttl`
  - `resi-cache.caches.<cacheName>.cache-null-values`
  - `resi-cache.caches.<cacheName>.key-prefix`
  - `resi-cache.caches.<cacheName>.enable-bloom-filter`
  - `resi-cache.caches.<cacheName>.enable-pre-refresh`
- Add serializer settings:
  - `resi-cache.serializer.allowed-package-prefixes`
  - `resi-cache.serializer.fail-on-unknown-type`
  - `resi-cache.serializer.type-property`
- Add cache manager settings:
  - `resi-cache.transaction-aware`
  - key prefix strategy
  - clear batch strategy
  - optional time-to-idle behavior if supported by the selected Spring Data
    Redis version.
- Add Redis deployment settings:
  - `resi-cache.redis.mode=single|cluster|sentinel`
  - username and password
  - TLS
  - database
  - read mode
  - subscription mode
  - Redisson YAML passthrough for advanced deployments.
- Fix configuration precedence:
  1. Annotation attributes
  2. Per-cache configuration
  3. Global ResiCache configuration
  4. Spring Data Redis defaults
- Allow user-provided `RedisCacheConfiguration`,
  `RedisCacheManagerBuilderCustomizer`, `RedisConnectionFactory`, and
  `RedissonClient` beans to take precedence over auto-configured defaults.

## Phase 4: Lightweight Dependency Model

- Make Redisson optional.
- Activate distributed lock support only when a `RedissonClient` bean exists or
  explicit Redisson configuration is enabled.
- Provide a no-op or local lock fallback for environments that do not include
  Redisson.
- Keep the main starter focused on Spring Cache and Spring Data Redis.
- Gate optional features with conditional auto-configuration:
  - Redisson lock
  - Bloom provider
  - Actuator health and metrics
  - advanced serializer support.
- Consider future module split only after correctness is fixed:
  - `resicache-core`
  - `resicache-redisson-lock`
  - `resicache-bloom`
  - `resicache-actuator`

## Phase 5: Serializer Hardening

- Add serializer properties to `RedisProCacheProperties`.
- Wire `allowed-package-prefixes` into
  `SecureJackson2JsonRedisSerializer`.
- Remove the broad `allowIfSubType(Object.class)` rule unless there is a
  documented and tested reason to keep it.
- Allow only safe JDK value types and explicitly configured domain packages.
- Add tests for:
  - allowed domain package deserialization
  - rejected unauthorized package deserialization
  - null values
  - collections and maps
  - old cached values during serializer migration.
- Document migration risks and recommended rollout for existing Redis data.

## Phase 6: Observability And Failure Modes

- Stop exposing local approximate `size` as exact cache size.
- Instrument all cache access paths:
  - `get(key)`
  - `get(key, type)`
  - `get(key, loader)`
  - `put`
  - `evict`
  - `clear`
- Prefer Micrometer meters and Spring Data Redis statistics where possible.
- Add metrics for:
  - hits
  - misses
  - puts
  - evictions
  - load duration
  - lock wait duration
  - lock acquisition failures
  - Bloom rejects
  - pre-refresh submitted, succeeded, and failed.
- Add explicit failure policies:
  - `fail-fast`
  - `fallback-to-method`
  - `return-stale-if-present`
- Cover Redis unavailable, serialization failure, lock timeout, and background
  refresh failure with tests.

## Phase 7: Documentation

- Update README so it describes only implemented and tested behavior.
- Add a Spring Cache compatibility table covering:
  - `@Cacheable`
  - `@CachePut`
  - `@CacheEvict`
  - `@Caching`
  - `key`
  - `keyGenerator`
  - `cacheManager`
  - `cacheResolver`
  - `condition`
  - `unless`
  - `sync`
- Add a compatibility matrix for:
  - Java
  - Spring Boot
  - Spring Framework
  - Spring Data Redis
  - Redis
  - Redisson.
- Add production examples for:
  - single Redis
  - Redis Cluster
  - Redis Sentinel
  - TLS
  - ACL username/password
  - custom serializer whitelist.
- Add docs for:
  - observability metric names
  - failure-mode behavior
  - known limitations
  - migration guide
  - semantic versioning policy.

## Test Plan

- Unit tests:
  - every annotation attribute maps into runtime metadata
  - `@RedisCachePut` creates a put operation
  - serializer whitelist accepts allowed packages and rejects unauthorized types
  - per-cache configuration precedence is correct
  - Redisson optional auto-configuration does not load when absent.
- Integration tests:
  - `condition=false` does not read or write cache
  - `unless=true` does not write cache after method execution
  - `@RedisCachePut` executes every call and updates Redis
  - `@RedisCacheEvict` supports key eviction, all entries, and before invocation
  - SpEL keys match actual Redis keys
  - custom `KeyGenerator` matches actual Redis keys
  - custom `CacheResolver` is honored
  - TTL, random TTL, null-value caching, Bloom, sync lock, and pre-refresh work
    through real annotated service calls.
- Enterprise tests:
  - single, cluster, and sentinel property binding
  - TLS and username property binding
  - user-provided bean precedence
  - Redis unavailable degradation policy
  - Micrometer metric names and counts.
- Acceptance commands:

```bash
./mvnw test
./mvnw verify
```

Coverage acceptance:

- JaCoCo line coverage: at least 80%
- JaCoCo branch coverage: at least 60%

## Assumptions

- ResiCache remains a Spring Cache plugin.
- Redisson and Bloom support are optional features, not mandatory base
  dependencies.
- Pre-refresh will be implemented as real background refresh, not renamed to
  early expiration.
- Breaking API changes are allowed for the next minor or major version if they
  are documented with migration guidance.
