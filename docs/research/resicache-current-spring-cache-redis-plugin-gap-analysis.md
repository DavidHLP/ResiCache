# ResiCache Current Spring Cache Redis Plugin Gap Analysis

Generated: 2026-05-23 11:38:00 CST

## Executive Summary

ResiCache has a strong feature direction: it attempts to enhance Spring Cache on
Redis with TTL jitter, null-value protection, Bloom filters, distributed locking,
pre-refresh, metrics, health checks, and SPI extension points. The current code
is more mature than the older gap analysis in this repository in one important
area: custom cache operations now extend Spring's concrete operation types such
as `CacheableOperation` and `CachePutOperation`, so the previous blanket concern
that custom operations cannot enter Spring's standard execution paths is no
longer accurate.

The remaining blockers are still substantial. The most important issues are not
missing edge features, but semantic mismatches with Spring Cache, misleading
protection behavior, incomplete production configuration wiring, serializer
migration/security risk, and failing integration tests. In its current shape,
ResiCache should be treated as an experimental Spring Redis cache enhancement
library rather than a production-ready Spring Cache Redis plugin.

## Research Scope

This report evaluates ResiCache as a Spring Cache Redis enhancement plugin. It
uses the following baselines:

- Spring Cache annotation semantics: `@Cacheable`, `@CachePut`, `@CacheEvict`,
  `@Caching`, custom composed annotations, `key`, `keyGenerator`,
  `cacheManager`, `cacheResolver`, `condition`, `unless`, `sync`, multiple cache
  names, and provider-backed cache lookup/loading behavior.
- Spring Data Redis cache behavior: `RedisCacheManager`, locking and
  non-locking `RedisCacheWriter`, transaction awareness, per-cache
  configuration, TTL, TTI via `GETEX`, null-value handling, key prefixes,
  serialization, cache-wide clear batch strategy, statistics, and asynchronous
  retrieval/store hooks.
- Production plugin expectations: light dependency surface, predictable
  auto-configuration, clear compatibility matrix, safe serializer defaults,
  observable failure modes, and a reliable test suite.

References:

- Spring Cache annotations:
  https://docs.spring.io/spring-framework/reference/integration/cache/annotations.html
- Spring Data Redis cache reference:
  https://docs.spring.io/spring-data/redis/reference/redis/redis-cache.html
- Spring support policy:
  https://spring.io/support-policy
- Spring Boot 3.2.4 release:
  https://spring.io/blog/2024/03/21/spring-boot-3-2-4-available-now
- OWASP deserialization risk:
  https://owasp.org/www-community/vulnerabilities/Deserialization_of_untrusted_data
- Jackson polymorphic deserialization CVE criteria:
  https://github.com/FasterXML/jackson/wiki/Jackson-Polymorphic-Deserialization-CVE-Criteria

## Current Architecture Snapshot

High-level flow:

```text
User method
  -> @RedisCacheable / @RedisCachePut / @RedisCacheEvict / @RedisCaching
  -> RedisCacheInterceptor
  -> AnnotationHandler chain
  -> RedisCacheRegister
  -> Spring CacheAspectSupport execution
  -> RedisProCacheManager
  -> RedisProCache
  -> RedisProCacheWriter
  -> CacheHandlerChain
  -> RedisTemplate operations
```

Handler chain order:

```text
BloomFilterHandler  -> SyncLockHandler -> PreRefreshHandler
                   -> TtlHandler      -> NullValueHandler
                   -> ActualCacheHandler
```

Key files:

- `src/main/java/io/github/davidhlp/spring/cache/redis/annotation/RedisCacheOperationSource.java`
- `src/main/java/io/github/davidhlp/spring/cache/redis/core/RedisCacheInterceptor.java`
- `src/main/java/io/github/davidhlp/spring/cache/redis/core/handler/CacheableAnnotationHandler.java`
- `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/RedisProCacheWriter.java`
- `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/BloomFilterHandler.java`
- `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/ActualCacheHandler.java`
- `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PreRefreshHandler.java`
- `src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProCacheConfiguration.java`
- `src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProCacheProperties.java`

## What Has Improved Compared With The Existing Gap Analysis

The repository already contains:

- `docs/research/resicache-enterprise-lightweight-gap-analysis.md`
- `docs/research/resicache-enterprise-lightweight-remediation-plan.md`

Some findings in those files are now partially outdated.

### Custom Operation Concrete Types Have Been Fixed

Current code:

- `RedisCacheableOperation extends CacheableOperation`
- `RedisCachePutOperation extends CachePutOperation`
- `RedisCacheEvictOperation` should be verified similarly, but the operation
  source currently returns dedicated evict operation instances.

This means Spring's cache aspect has a better chance of routing custom
operations through the intended standard cacheable/put/evict paths. The original
P0 issue that custom operations merely extend `CacheOperation` is no longer the
main concern.

### Annotation Feature Fields Are Now Parsed More Completely

`RedisCacheOperationSource` now copies ResiCache-specific fields such as:

- `ttl`
- `type`
- `cacheNullValues`
- `useBloomFilter`
- `randomTtl`
- `variance`
- `enablePreRefresh`
- `preRefreshThreshold`
- `preRefreshMode`
- `syncTimeout`

This addresses part of the older concern that annotation attributes were
declared but not transferred into operation metadata.

### Redis Deployment Configuration Is Broader Than Before

`RedisConnectionConfiguration` now includes code paths for:

- single-server mode
- cluster mode
- sentinel mode
- TLS scheme selection
- username/password
- user-supplied Redisson YAML config path

This is materially better than a single-server-only implementation. It still
needs stronger integration and compatibility tests, but the design surface is no
longer absent.

## Critical Defects

### 1. Key Resolution Still Does Not Fully Match Spring Cache

File:

- `src/main/java/io/github/davidhlp/spring/cache/redis/core/handler/CacheableAnnotationHandler.java`

Current behavior:

- `CacheableAnnotationHandler.resolveKey(...)` manually evaluates the annotation
  `key` SpEL expression.
- It creates a `StandardEvaluationContext`, binds `root`, `#a0`, `#p0`, and
  reflected parameter names when available.
- It falls back to the configured `KeyGenerator`.

Risk:

Spring Cache has a mature expression evaluation path with a specific root object,
parameter name discovery, result handling, cache metadata, and special
semantics. Reimplementing part of that path can produce metadata keys that differ
from the actual keys Spring uses when invoking the cache operation. If
`RedisCacheRegister` stores metadata under one key while `RedisProCacheWriter`
looks it up under another key, ResiCache-specific features silently disappear.

Affected features:

- TTL from annotation
- random TTL
- null-value caching
- Bloom filter
- sync lock
- pre-refresh

Likely failure cases:

- Java compiled without `-parameters`
- complex SpEL expressions
- class-level cache annotations
- custom `KeyGenerator`
- custom `CacheResolver`
- multiple cache names
- composed annotations
- `#root.caches`, `#root.targetClass`, `#root.methodName`, or other Spring
  expression features

Recommendation:

- Reuse Spring's cache operation key generation/evaluation machinery instead of
  manually evaluating key expressions.
- If direct reuse is impractical, store ResiCache metadata by operation identity
  rather than generated cache key.
- Add integration tests that compare metadata lookup keys against actual Redis
  keys for `key`, `keyGenerator`, class-level annotations, multiple arguments,
  and multiple cache names.

### 2. Bloom Filter Does Not Actually Prevent Cache Penetration In Standard Spring Cache Flow

File:

- `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/BloomFilterHandler.java`

Current behavior:

- On GET, `BloomFilterHandler` checks whether a key might exist.
- If Bloom returns negative, it terminates the writer chain with
  `CacheResult.rejectedByBloomFilter()`.
- `RedisProCacheWriter.get(...)` returns `result.getResultBytes()`, which is
  `null`.

Why this is insufficient:

In Spring Cache, a cache GET returning `null` is normally a cache miss. For a
`@Cacheable` method, Spring then invokes the user method. If the user method
queries the database, the Bloom filter did not prevent database penetration; it
only skipped Redis lookup work after already entering the cache path.

This is a core product-semantics problem. The README says:

```text
BloomFilter -> does not exist -> return null directly, do not query cache
```

But the important production promise should be "do not query the origin
database/service for impossible keys." The current writer-level Bloom filter
cannot guarantee that in the standard Spring `@Cacheable` flow.

Recommendation:

- Decide the intended contract:
  - If Bloom only avoids Redis work, document it honestly.
  - If Bloom should prevent DB penetration, integrate it before loader
    invocation in the cache aspect path.
- Provide a trusted existence source or explicit preload API for Bloom filters.
- Add service-level tests proving that a Bloom-negative request does not invoke
  the annotated method.

### 3. Bloom Filter False-Positive Handling Pollutes The Filter

File:

- `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/BloomFilterHandler.java`

Current behavior:

When Bloom returns positive but Redis cache misses, post-processing adds the key
to the Bloom filter:

```text
if (!result.isHit()) {
    bloomSupport.add(cacheName, actualKey)
}
```

Risk:

This is backwards for cache penetration prevention. A Bloom positive but cache
miss may mean:

- the key truly exists but the cache entry expired
- the Bloom filter returned a false positive
- cache was evicted independently
- data was deleted from the source

Adding a key on a cache miss without confirming source existence can permanently
increase false positives. Over time, nonexistent keys can be retained in the
filter, and the filter becomes less useful as a protection mechanism.

Recommendation:

- Only add a key to Bloom after a successful origin load or explicit existence
  confirmation.
- Do not add a key after a cache miss alone.
- Add tests for false-positive keys and deleted keys.

### 4. Cache Hit Path Performs Redis Writes

File:

- `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/ActualCacheHandler.java`

Current behavior:

On every cache hit:

```text
cachedValue.withAccessUpdate()
valueOperations.setIfPresent(redisKey, updatedValue, remainingTtl)
```

Risk:

Every read becomes a Redis write. That increases:

- write QPS
- replication traffic
- AOF/RDB churn
- latency tail
- CPU usage
- failure surface for read-only cache consumers

This design also conflates metadata tracking with cache access. If the goal is
TTI, Spring Data Redis has a formal `enableTimeToIdle()` path using Redis
`GETEX`. If the goal is access statistics, use metrics rather than rewriting the
value on every hit.

Recommendation:

- Do not update `lastAccessTime` and `visitTimes` inside the cached value on
  every hit by default.
- Make this behavior opt-in if it is needed.
- For TTI, align with Spring Data Redis `enableTimeToIdle()` / `GETEX`.
- For access metrics, use `CacheStatisticsCollector` and Micrometer counters.

### 5. Pre-Refresh Is Actually Early Expiration, Not Background Refresh

File:

- `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PreRefreshHandler.java`

Current behavior:

- Sync mode marks the cache hit as a miss so the caller reloads.
- Async mode schedules a task that fetches the live cached value and shortens its
  TTL to a grace period.
- It does not call the original method or a loader callback to rebuild the cache
  in the background.

Risk:

Calling this feature "pre-refresh" creates incorrect user expectations. A user
will expect hot keys to be refreshed before expiry. The implementation instead
causes earlier expiration or caller-driven reload.

Recommendation:

- Rename the feature to early expiration if this behavior is intentional.
- Or implement true refresh:
  - capture a safe refresh callback,
  - apply single-flight protection,
  - refresh in a bounded executor,
  - record success/failure metrics,
  - define what happens when refresh fails.

### 6. Sync Lock Is Applied At The Writer Layer, Which Is Too Late For Full Single-Flight Semantics

Files:

- `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/SyncLockHandler.java`
- `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/RedisProCacheWriter.java`

Risk:

Spring's `@Cacheable(sync=true)` is intended to synchronize the cache loading
path so only one thread computes the value for a cache miss. A lock inside the
writer chain can protect Redis operations, but it may not protect the full
method-loader invocation unless the cache provider's `get(key, Callable)`
contract is correctly integrated.

Recommendation:

- Add concurrency integration tests around an annotated method with
  `sync=true`, not only direct writer/lock tests.
- Assert only one method invocation happens under concurrent cache miss.
- Ensure lock timeout and fallback behavior are configurable and documented.

### 7. Configuration Properties Are Declared But Not Fully Wired

File:

- `src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProCacheProperties.java`

Declared but weakly or not fully applied:

- `transactionAware`
- `keyPrefix`
- `caches`
- `disabledHandlers`
- `handlerSettings`
- `serializer.failOnUnknownType`
- `serializer.typeProperty`

File:

- `src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProCacheConfiguration.java`

Current default Redis cache configuration uses:

- default TTL
- key serializer
- value serializer

It does not visibly apply:

- transaction awareness
- per-cache configuration map
- key prefix
- null-value policy from per-cache config
- handler enablement matrix
- TTI
- batch strategy

Recommendation:

- Treat Spring Data Redis features as the base, then layer ResiCache-specific
  protections on top.
- Build `RedisCacheManager` through a builder path that applies:
  - transaction awareness,
  - initial cache configurations,
  - per-cache TTL,
  - null-value behavior,
  - prefix strategy,
  - statistics,
  - batch strategy.
- Add configuration binding tests and runtime integration tests for every public
  property.

### 8. Serializer Strategy Remains Risky For A General-Purpose Plugin

File:

- `src/main/java/io/github/davidhlp/spring/cache/redis/config/SecureJackson2JsonRedisSerializer.java`

Current behavior:

- Clones the `ObjectMapper`.
- Activates Jackson default typing for non-final types.
- Restricts deserialization through a package-prefix validator and allows common
  JDK packages.

Risk:

This is safer than unrestricted default typing, but it is still a high-risk
serializer shape for a general-purpose cache plugin. If Redis is compromised or
shared with untrusted writers, cached payloads become a deserialization boundary.
The package-prefix configuration also creates operational friction: users must
know to whitelist their domain packages before cached objects can be read.

Missing:

- cache format versioning
- migration guide
- compatibility tests across releases
- behavior for unknown type when `failOnUnknownType` is false
- use of configured `typeProperty`
- clear recommendation for non-polymorphic serializers

Recommendation:

- Prefer a non-default-typing serializer path by default.
- Make polymorphic typing opt-in and loudly documented.
- Add explicit value envelope versioning if ResiCache needs to wrap cached
  values.
- Add tests for domain package allow-listing, unknown type handling, and
  cross-version payload compatibility.

### 9. Dependency Surface Is Not Truly Lightweight

File:

- `pom.xml`

Observed:

- Spring Boot parent: `3.2.4`
- Redisson: `3.27.0`
- Actuator dependency is optional.
- Redisson dependency is optional.

Risk:

Optional Maven dependencies are only part of the story. The code still imports
and wires optional-type features in core configuration paths. For a lightweight
starter, the library should work with the smallest useful dependency surface:

- Spring Cache
- Spring Data Redis
- a Redis client through Boot's normal auto-configuration

Redisson, Actuator, Micrometer, Bloom backends, and advanced metrics should be
strictly conditional add-ons.

Recommendation:

- Split optional integrations into separate auto-configurations guarded by
  `@ConditionalOnClass`.
- Ensure the starter can boot without Redisson and without Actuator.
- Consider separate artifacts if dependency isolation becomes hard:
  - `resicache-spring-boot-starter`
  - `resicache-redisson`
  - `resicache-actuator`

### 10. Spring Boot Baseline Is Old For A 2026 Plugin

File:

- `pom.xml`

Current baseline:

- Spring Boot `3.2.4`

This version was released on 2024-03-21. Spring's OSS support policy states that
open source support for a major/minor generation lasts at least 12 months from
release or at least 3 months after the next minor release, whichever is longer.
For a 2026 plugin, Boot 3.2.x is no longer a good default development baseline.

Recommendation:

- Define a compatibility matrix.
- Build/test against at least:
  - currently supported Spring Boot 3.x line,
  - latest Spring Data Redis line compatible with that Boot line,
  - Redis 6.2+ if TTI is supported,
  - Redis 7.x as the main target.
- Avoid unnecessary APIs that pin the plugin to a narrow Boot patch line.

## Missing Capabilities

### 1. Native Spring Annotation Enhancement

README quick start uses Spring's native `@Cacheable`, but ResiCache-specific
features are only exposed through custom annotations such as `@RedisCacheable`.

Missing:

- Clear compatibility table for native `@Cacheable`.
- A supported way to apply ResiCache protections to native Spring annotations.
- Migration path from `@Cacheable` to `@RedisCacheable`.
- Meta-annotation support documentation.

Recommendation:

- Either position ResiCache as a custom annotation framework, or provide a clear
  enhancement layer for native Spring annotations through cache name policy or
  external configuration.

### 2. Real Loader-Aware Protection

For anti-penetration, anti-breakdown, and pre-refresh, the decisive point is the
origin loader invocation, not only Redis read/write.

Missing:

- loader-aware Bloom short-circuit
- loader-aware single-flight
- loader-aware background refresh
- explicit origin existence strategy

Recommendation:

- Move protection decisions closer to `CacheAspectSupport` / cache provider
  callback semantics.
- Add tests that count actual annotated method invocations.

### 3. First-Class Failure Policy

Missing configuration:

- Redis unavailable: fail open, fail closed, bypass cache, or throw?
- serialization failure: evict bad value, throw, bypass, or return miss?
- lock timeout: proceed without lock, fail, or return stale value?
- Bloom backend failure: assume may-contain or reject?
- pre-refresh failure: log only, metric, retry, or propagate?

Current code has local fallback choices in different classes, but there is no
unified documented policy.

### 4. L1/L2 Cache Strategy

The project includes Caffeine as a dependency and has local Bloom structures,
but does not present a coherent L1/L2 cache feature.

Missing:

- local cache layer
- invalidation through Redis Pub/Sub or keyspace notifications
- per-cache local TTL/size
- stale-read policy
- consistency guarantees

This may be out of scope for a minimal plugin, but it is a common expectation
for "Spring Redis cache enhancement" libraries.

### 5. Production Observability

Current observability is partial:

- `RedisProCache` registers custom Micrometer timers/counters.
- `CacheStatisticsCollector` is used in writer handlers.
- `MetricsAutoConfiguration` is gated by `resi-cache.metrics.enabled=true`.

Missing:

- consistent metric naming
- bounded tag cardinality guidance
- lock wait/acquire/fail metrics
- Bloom false-positive and rejection metrics
- pre-refresh submitted/succeeded/failed metrics
- serialization failure metrics
- Redis fallback/degradation metrics
- documented actuator endpoint behavior

### 6. Performance Benchmarks

Missing:

- baseline Spring Data Redis cache comparison
- hit-path overhead measurement
- miss-path overhead measurement
- hit-path write amplification measurement
- Bloom filter memory/latency benchmark
- lock contention benchmark
- cluster/sentinel latency benchmark

This matters because several ResiCache features increase work per cache access.

### 7. Compatibility And Release Documentation

Missing docs:

- Java compatibility
- Spring Boot compatibility
- Spring Data Redis compatibility
- Redis server compatibility
- Redisson compatibility
- Lettuce/Jedis notes
- Redis Cluster/Sentinel/TLS tested matrix
- Maven Central release status
- semantic versioning policy
- cache payload migration guide
- known limitations

## Test And Verification Findings

Command run:

```bash
./mvnw test -q
```

Result:

```text
Tests run: 687, Failures: 0, Errors: 24, Skipped: 0
```

Primary root cause from Surefire reports:

```text
Error creating bean with name 'processorMetrics'
Cannot invoke "jdk.internal.platform.CgroupInfo.getMountPoint()" because "anyController" is null
```

This happens during Spring Boot Actuator system metrics auto-configuration in
integration test context startup. Many later errors are cascading Spring Test
context failures:

```text
ApplicationContext failure threshold (1) exceeded
```

Implications:

- Current test suite is not green in this environment.
- Integration verification is fragile because optional metrics/actuator
  behavior can break cache integration tests.
- The test suite has many useful unit tests, but the current failure prevents
  treating the build as production-ready.

Coverage report snapshot from `target/site/jacoco/jacoco.xml`:

```text
LINE missed=745 covered=2087
Approximate line coverage = 2087 / (2087 + 745) = 73.7%
BRANCH missed=580 covered=496
Approximate branch coverage = 496 / (496 + 580) = 46.1%
```

This is below the repository's stated 80%+ coverage expectation.

Recommendations:

- Disable Actuator system metrics in integration tests unless explicitly under
  test.
- Keep cache integration tests focused on cache behavior, not host/container JVM
  metrics.
- Add annotated-service tests for:
  - `@RedisCacheable key`
  - `condition=false`
  - `unless=true`
  - `sync=true`
  - multiple cache names
  - custom key generator
  - null-value caching
  - Bloom negative should or should not invoke loader, depending on final
    product contract
  - TTL and random TTL
  - pre-refresh behavior
  - evict and put semantics

## Prioritized Remediation Plan

### P0: Correctness And Trust

1. Make the test suite green.
2. Decide ResiCache's semantic contract:
   - Spring Cache-compatible extension, or
   - custom cache annotation framework.
3. Replace manual key evaluation with Spring-compatible key resolution or
   metadata lookup by operation identity.
4. Fix Bloom filter semantics:
   - do not add keys on cache miss alone,
   - prove whether Bloom prevents loader invocation,
   - document exact behavior.
5. Verify `sync=true` with annotated service concurrency tests.
6. Stop writing Redis on every cache hit by default.

### P1: Production Configuration

1. Wire declared properties into actual cache manager/writer behavior.
2. Apply transaction awareness and per-cache configuration.
3. Support key prefix strategy properly.
4. Align with Spring Data Redis TTI and batch clear strategy.
5. Make Redisson, Actuator, and Micrometer strictly optional.
6. Add a unified failure policy.

### P2: Feature Honesty And Depth

1. Rename pre-refresh to early expiration or implement true background refresh.
2. Add loader-aware refresh and single-flight if retaining the current feature
   promises.
3. Add observability for lock, Bloom, serializer, refresh, and fallback behavior.
4. Add performance benchmarks against plain Spring Data Redis cache.
5. Add compatibility matrix and migration docs.

### P3: Market-Grade Plugin Polish

1. Split optional integrations if needed.
2. Provide sample applications:
   - minimal Boot app,
   - Redisson lock app,
   - cluster/sentinel app,
   - actuator metrics app.
3. Add release automation checks for:
   - tests,
   - coverage,
   - dependency audit,
   - source/javadoc jars,
   - reproducible package content.

## Decision Recommendation

For the next iteration, avoid adding more headline features. The highest leverage
work is to make the plugin semantically boring and correct:

- Spring Cache-compatible behavior first.
- Redis access pattern efficiency second.
- Failure policy and configuration completeness third.
- Only then add advanced features such as true background refresh and local
  multi-level caching.

The current implementation already has enough surface area. The gap is not
ambition; the gap is making each advertised capability precise, testable, and
aligned with Spring's cache contract.
