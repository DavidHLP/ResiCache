# Plan: ResiCache Defect Remediation — Spring Cache Redis Enhancement Plugin

## Summary

This plan addresses critical semantic mismatches, performance defects, and production-readiness gaps in ResiCache, a Spring Cache Redis enhancement plugin. The work is organized into 4 parent workstreams and 17 atomic tasks. Each task is designed to be completable by a single developer within 0.5–2 days, with strict quality gates.

## User Story

As a Spring Boot developer using ResiCache,
I want the plugin to behave as a transparent, semantically correct enhancement to Spring Cache on Redis,
So that I can trust its advertised protections (TTL jitter, Bloom filter, sync locking, pre-refresh) without silent failures or performance regressions.

## Problem → Solution

**Current State**: ResiCache has ~74% line coverage, 24 test errors, key-resolution mismatches with Spring Cache, Bloom filter at wrong layer, read-path write amplification, and many declared config properties that are not wired. It behaves as an experimental framework rather than a production plugin.

**Desired State**: All tests pass, key resolution matches Spring Cache semantics, Bloom filter prevents loader invocation, read path is read-only by default, all declared properties are wired, and the plugin boots with only Spring Cache + Spring Data Redis on the classpath.

## Metadata
- **Complexity**: XL (architectural fixes, cross-cutting concerns, 20+ files)
- **Source PRD**: `docs/research/resicache-deep-defect-analysis.md`, `docs/research/resicache-current-spring-cache-redis-plugin-gap-analysis.md`
- **PRD Phase**: standalone remediation
- **Estimated Files**: 25+

---

## UX Design

### Before
```
┌─────────────────────────────────────────────────────────────┐
│  Developer adds @RedisCacheable(sync=true, useBloomFilter=true)  │
│  ├─ Key may not match Spring's actual cache key            │
│  ├─ Bloom filter only skips Redis, DB still hit            │
│  ├─ Every cache read triggers a Redis write                │
│  ├─ sync=true does not prevent concurrent method calls     │
│  ├─ Pre-refresh causes early expiration, not background rebuild │
│  └─ Many config properties silently ignored                │
└─────────────────────────────────────────────────────────────┘
```

### After
```
┌─────────────────────────────────────────────────────────────┐
│  Developer adds @RedisCacheable(sync=true, useBloomFilter=true)  │
│  ├─ Key generation 100% compatible with Spring Cache       │
│  ├─ Bloom negative short-circuits before loader invocation │
│  ├─ Read path is read-only by default                      │
│  ├─ sync=true guarantees single-flight method invocation   │
│  ├─ Early-expiration behavior is honestly named and documented │
│  └─ Every config property is validated and wired           │
└─────────────────────────────────────────────────────────────┘
```

### Interaction Changes
| Touchpoint | Before | After | Notes |
|---|---|---|---|
| `@RedisCacheable` key resolution | Manual SpEL in `CacheableAnnotationHandler` | Spring-compatible via `AnnotatedElementKey` | Eliminates silent metadata mismatch |
| `sync=true` | Lock in writer layer (`SyncLockHandler`) | Lock in `get(name, key, valueLoader)` | Prevents concurrent method calls |
| `useBloomFilter=true` | Writer-layer GET rejection | Loader-layer short-circuit | Actually prevents DB penetration |
| Cache hit | `setIfPresent` rewrite on every read | No write by default | Eliminates write amplification |
| `enablePreRefresh` | Early expiration / miss injection | Renamed to `enableEarlyExpiration` | Honest naming |
| `resi-cache.*` properties | Many declared, few wired | All validated and applied | Trust in configuration |

---

## Mandatory Reading

Files that MUST be read before implementing:

| Priority | File | Lines | Why |
|---|---|---|---|
| P0 (critical) | `src/main/java/io/github/davidhlp/spring/cache/redis/core/handler/CacheableAnnotationHandler.java` | all | Manual key resolution that must be replaced |
| P0 (critical) | `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/RedisProCacheWriter.java` | all | Writer layer where metadata lookup and Bloom are applied |
| P0 (critical) | `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/BloomFilterHandler.java` | all | Bloom semantics at wrong layer |
| P0 (critical) | `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/ActualCacheHandler.java` | all | Read-path write amplification |
| P0 (critical) | `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/SyncLockHandler.java` | all | Lock at writer layer, too late |
| P0 (critical) | `src/main/java/io/github/davidhlp/spring/cache/redis/register/RedisCacheRegister.java` | all | Metadata registry with broken lookup key |
| P0 (critical) | `src/main/java/io/github/davidhlp/spring/cache/redis/core/RedisCacheInterceptor.java` | all | Custom interceptor that manually resolves keys too early |
| P1 (important) | `src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProCacheConfiguration.java` | all | Where properties must be wired |
| P1 (important) | `src/main/java/io/github/davidhlp/spring/cache/redis/manager/RedisProCacheManager.java` | all | CacheManager that ignores transactionAware and per-cache config |
| P1 (important) | `src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProCacheProperties.java` | all | Declared but unwired properties |
| P2 (reference) | `src/main/java/io/github/davidhlp/spring/cache/redis/annotation/RedisCacheOperationSource.java` | all | Annotation parsing; native Spring annotation support is stubbed |
| P2 (reference) | `src/main/java/io/github/davidhlp/spring/cache/redis/core/RedisProCache.java` | all | Metrics duplication with writer handlers |
| P2 (reference) | `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PreRefreshHandler.java` | all | Early expiration mislabeled as pre-refresh |
| P2 (reference) | `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/CachedValue.java` | all | Immutable value wrapper; version field unused by serializer |

## External Documentation

| Topic | Source | Key Takeaway |
|---|---|---|
| Spring Cache key generation | `CacheAspectSupport.java` (spring-context 6.2.17) | Uses `CacheOperationExpressionEvaluator` with `CacheEvaluationContext`; metadata cached by `CacheOperationCacheKey` |
| Spring Data Redis CacheManager builder | `RedisCacheManager.java` (spring-data-redis 3.5.10) | Use `RedisCacheManager.builder(cacheWriter)` to set transactionAware, initialCacheConfigurations, etc. |
| Spring Data Redis sync loading | `RedisCacheWriter.get(name, key, valueLoader, ttl, tti)` | Default implementation uses Redis `SET NX` for single-flight loading; must be overridden for custom sync behavior |
| Spring Data Redis TTI | `RedisCacheConfiguration.enableTimeToIdle()` | Uses Redis 6.2+ `GETEX` to refresh TTL on read without rewriting value |

---

## Patterns to Mirror

### NAMING_CONVENTION
// SOURCE: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/BloomFilterHandler.java:1`
```java
package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@HandlerPriority(HandlerOrder.BLOOM_FILTER)
public class BloomFilterHandler extends AbstractCacheHandler implements PostProcessHandler {
```
Pattern: PascalCase classes, camelCase methods/fields, Lombok `@Slf4j` + `@RequiredArgsConstructor`, Spring `@Component`, custom `@HandlerPriority`.

### ERROR_HANDLING
// SOURCE: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/ActualCacheHandler.java:117-119`
```java
try {
    ...
} catch (Exception e) {
    return errorHandler.handleGetError(context.getCacheName(), context.getRedisKey(), e);
}
```
Pattern: Try/catch at operation boundary, delegate to `CacheErrorHandler` abstraction, return `CacheResult` instead of throwing.

### LOGGING_PATTERN
// SOURCE: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/BloomFilterHandler.java:78-84`
```java
log.debug(
    "Bloom filter rejected (key does not exist): cacheName={}, key={}",
    context.getCacheName(),
    context.getRedisKey());
```
Pattern: SLF4J parameterized logging, include `cacheName` and `key` in every debug log, use Chinese comments for design rationale in class-level Javadoc.

### TEST_STRUCTURE
// SOURCE: inferred from `src/test/java` structure and `AbstractRedisIntegrationTest.java`
Pattern: Test classes mirror source package structure. Integration tests extend `AbstractRedisIntegrationTest` and use Testcontainers for Redis. Unit tests use JUnit 5 + AssertJ.

---

## Files to Change

| File | Action | Justification |
|---|---|---|
| `src/main/java/io/github/davidhlp/spring/cache/redis/core/handler/CacheableAnnotationHandler.java` | DELETE or DEPRECATE | Manual key resolution is replaced by metadata lookup |
| `src/main/java/io/github/davidhlp/spring/cache/redis/core/RedisCacheInterceptor.java` | UPDATE | Remove manual key resolution; keep registration via operation identity |
| `src/main/java/io/github/davidhlp/spring/cache/redis/register/RedisCacheRegister.java` | UPDATE | Change lookup key from generated cache key to `AnnotatedElementKey` or operation hash |
| `src/main/java/io/github/davidhlp/spring/cache/redis/register/operation/RedisCacheableOperation.java` | UPDATE | Add `equals`/`hashCode` based on `(Method, Class<?>)` if needed for lookup |
| `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/RedisProCacheWriter.java` | UPDATE | Add `get(name, key, valueLoader, ttl, tti)` override; change metadata lookup key |
| `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/BloomFilterHandler.java` | UPDATE | Remove GET-layer Bloom; move loader-aware Bloom to `RedisProCache.get(key, Callable)` |
| `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/ActualCacheHandler.java` | UPDATE | Remove `withAccessUpdate` + `setIfPresent` from hit path |
| `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/SyncLockHandler.java` | DELETE or REDUCE | Replaced by writer-level single-flight in `get(name, key, valueLoader, ...)` |
| `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PreRefreshHandler.java` | UPDATE | Rename feature references to `earlyExpiration` |
| `src/main/java/io/github/davidhlp/spring/cache/redis/core/RedisProCache.java` | UPDATE | Override `get(key, Callable)` for Bloom + sync; deduplicate metrics |
| `src/main/java/io/github/davidhlp/spring/cache/redis/manager/RedisProCacheManager.java` | UPDATE | Use builder pattern; wire transactionAware and per-cache configs |
| `src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProCacheConfiguration.java` | UPDATE | Wire all declared properties; make optional dependencies conditional |
| `src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProCacheProperties.java` | UPDATE | Add `@Validated` and `@NotNull` constraints |
| `src/main/java/io/github/davidhlp/spring/cache/redis/config/SecureJackson2JsonRedisSerializer.java` | UPDATE | Default to non-polymorphic; add version envelope support |
| `src/main/java/io/github/davidhlp/spring/cache/redis/annotation/RedisCacheOperationSource.java` | UPDATE | Implement native Spring annotation forwarding |
| `pom.xml` | UPDATE | Upgrade Spring Boot baseline; split optional dependencies |
| `src/test/java/.../AbstractRedisIntegrationTest.java` | UPDATE | Exclude Actuator metrics from test context |
| `src/test/java/.../RedisProCacheWriterIntegrationTest.java` | CREATE | Test sync single-flight, Bloom loader short-circuit |
| `src/test/java/.../KeyResolutionIntegrationTest.java` | CREATE | Assert ResiCache metadata key == Spring cache key |

## NOT Building

- True background refresh (refresh-ahead) with captured loader callbacks — out of scope; will be renamed to honest "early expiration" instead
- L1/L2 local cache layer with Redis Pub/Sub invalidation — out of scope for this remediation
- Multi-artifact split (`resicache-redisson`, `resicache-micrometer`) — documented as P3 recommendation but not implemented in this plan
- Full Reactive (Mono/Flux) cache support — documented as limitation, not implemented
- Performance benchmark harness — documented as P3, not implemented in this plan

---

## Step-by-Step Tasks

---

### TASK-001: [Testing] Isolate Actuator and Fix Test Suite
- **ACTION**: Fix the 24 test errors by excluding Actuator system metrics from integration test contexts and ensuring the build passes cleanly.
- **IMPLEMENT**:
  1. In `AbstractRedisIntegrationTest` or test-specific `@TestConfiguration`, add property `management.metrics.enabled=false` or exclude `MetricsAutoConfiguration`.
  2. If `ProcessorMetrics` bean creation is triggered by a general `@SpringBootTest` scan, narrow the test context to only cache-relevant beans.
  3. Run `./mvnw test -q` and verify `Tests run: N, Failures: 0, Errors: 0, Skipped: 0`.
- **MIRROR**: Follow existing test structure under `src/test/java/io/github/davidhlp/spring/cache/redis`.
- **IMPORTS**: `org.springframework.boot.test.context.TestConfiguration`, `org.springframework.boot.autoconfigure.EnableAutoConfiguration`.
- **GOTCHA**: Do not use `@SpringBootTest` with the full application context unless necessary; use `@ContextConfiguration` with a minimal cache test config.
- **VALIDATE**: `./mvnw test -q` produces zero errors and zero failures.

#### Workflow & DoD
1. 🧪 【初步自测】
   - 开发者完成编码后，必须针对「交付物标准」进行本地功能自测或编写单元测试。
   
2. 🔍 【提交代码审查 (CR)】
   - 发起 Pull Request (PR) / Merge Request (MR)，邀请核心团队成员进行代码评审，并完整记录 CR 反馈意见。

3. 🛠️ 【修复 CR 意见 (Fix CR)】
   - 针对评审中发现的性能、安全、规范等问题逐项修复，禁止遗漏任何一条 blocking 意见。

4. 🔄 【回归测试】
   - 对修复后的代码再次进行本地回归测试，确保没有引入新的破坏性变更（Regression Bug）。

5. 🏁 【流程终止与提交条件】
   - **准出条件（DoD）**：只有当所有 CR 意见全部闭环、Reviewer 给出 Approved 状态，且回归测试 100% 通过时，方可执行最后一步。
   - **最终操作**：将代码正式 Commit 并合并至主分支，更新任务状态为“已完成”。

---

### TASK-002: [Core] Replace Generated-Key Metadata Lookup with AnnotatedElementKey
- **ACTION**: Change `RedisCacheRegister` to use `(Method, Class<?>)` — an `AnnotatedElementKey` — as the lookup key for `RedisCacheableOperation`, instead of the generated cache key string.
- **IMPLEMENT**:
  1. Update `RedisCacheRegister` methods to accept `AnnotatedElementKey` (or a wrapper containing `Method` + `Class<?>` + cacheName) instead of `String key`.
  2. Update `CacheableAnnotationHandler` to register operations using `new AnnotatedElementKey(method, targetClass)`.
  3. Update `RedisProCacheWriter.buildContext()` to construct the lookup key from the calling `Method` and target class, not from `extractActualKey()`.
  4. Ensure `RedisCacheableOperation` can be looked up by operation identity rather than evaluated SpEL result.
- **MIRROR**: Follow Spring's `CacheOperationCacheKey` pattern (composed of `CacheOperation`, `Method`, `Class<?>`).
- **IMPORTS**: `org.springframework.context.expression.AnnotatedElementKey`.
- **GOTCHA**: `AnnotatedElementKey` does not include cacheName; you may need a composite key like `CompositeKey(AnnotatedElementKey, cacheName)`.
- **VALIDATE**: Integration test asserting that for a method annotated with `@RedisCacheable(key = "#id")`, the metadata is found regardless of whether the actual argument `id` evaluates to `"123"` or `"456"`.

#### Workflow & DoD
1. 🧪 【初步自测】
   - 开发者完成编码后，必须针对「交付物标准」进行本地功能自测或编写单元测试。
   
2. 🔍 【提交代码审查 (CR)】
   - 发起 Pull Request (PR) / Merge Request (MR)，邀请核心团队成员进行代码评审，并完整记录 CR 反馈意见。

3. 🛠️ 【修复 CR 意见 (Fix CR)】
   - 针对评审中发现的性能、安全、规范等问题逐项修复，禁止遗漏任何一条 blocking 意见。

4. 🔄 【回归测试】
   - 对修复后的代码再次进行本地回归测试，确保没有引入新的破坏性变更（Regression Bug）。

5. 🏁 【流程终止与提交条件】
   - **准出条件（DoD）**：只有当所有 CR 意见全部闭环、Reviewer 给出 Approved 状态，且回归测试 100% 通过时，方可执行最后一步。
   - **最终操作**：将代码正式 Commit 并合并至主分支，更新任务状态为“已完成”。

---

### TASK-003: [Core] Remove Manual Key Resolution from CacheableAnnotationHandler
- **ACTION**: Delete the manual SpEL key evaluation in `CacheableAnnotationHandler.resolveKey()` and `bindMethodParameters()`. The key resolution should be left entirely to Spring's `CacheAspectSupport`.
- **IMPLEMENT**:
  1. Remove `resolveKey()`, `bindMethodParameters()`, and the inner `RootObject` class.
  2. Change `registerCacheableOperation()` to no longer compute a key at registration time; just pass the annotation and method reference to the factory.
  3. Update `RedisCacheInterceptor` to not rely on pre-resolved keys; let it only register operation metadata by identity.
- **MIRROR**: Spring `CacheAspectSupport` does not evaluate keys during operation source parsing — it evaluates them during aspect execution.
- **IMPORTS**: None new; remove `SpelExpressionParser`, `StandardEvaluationContext`.
- **GOTCHA**: Ensure `RedisCacheInterceptor` still calls `handlerChain.handle(method, target, args)` for metadata registration, but does not attempt to pre-compute keys.
- **VALIDATE**: Unit test that `CacheableAnnotationHandler` no longer contains `SpelExpressionParser` usage; integration test with complex SpEL (`#root.caches`, `@beanName`) works correctly.

#### Workflow & DoD
1. 🧪 【初步自测】
   - 开发者完成编码后，必须针对「交付物标准」进行本地功能自测或编写单元测试。
   
2. 🔍 【提交代码审查 (CR)】
   - 发起 Pull Request (PR) / Merge Request (MR)，邀请核心团队成员进行代码评审，并完整记录 CR 反馈意见。

3. 🛠️ 【修复 CR 意见 (Fix CR)】
   - 针对评审中发现的性能、安全、规范等问题逐项修复，禁止遗漏任何一条 blocking 意见。

4. 🔄 【回归测试】
   - 对修复后的代码再次进行本地回归测试，确保没有引入新的破坏性变更（Regression Bug）。

5. 🏁 【流程终止与提交条件】
   - **准出条件（DoD）**：只有当所有 CR 意见全部闭环、Reviewer 给出 Approved 状态，且回归测试 100% 通过时，方可执行最后一步。
   - **最终操作**：将代码正式 Commit 并合并至主分支，更新任务状态为“已完成”。

---

### TASK-004: [Core] Move Bloom Filter to Loader-Aware Layer
- **ACTION**: Remove Bloom filter GET check from `BloomFilterHandler` (writer layer) and implement it in `RedisProCache.get(key, Callable<T> valueLoader)` so that a Bloom-negative request never invokes the loader callback.
- **IMPLEMENT**:
  1. In `BloomFilterHandler.handleGet()`, change behavior to `HandlerResult.continueChain()` (pass through) instead of terminating with miss. Remove the GET bloom rejection logic.
  2. In `RedisProCache`, override `public <T> T get(Object key, Callable<T> valueLoader)`.
  3. Before calling `super.get(key, valueLoader)`, look up the `RedisCacheableOperation` metadata by `AnnotatedElementKey`.
  4. If `useBloomFilter` is true and `bloomSupport.mightContain(cacheName, key)` is false, return `null` (or a `NullValue` wrapper) **without calling `super.get(key, valueLoader)`**.
  5. Ensure `BloomFilterHandler` still adds keys to Bloom on successful PUT (this part is correct).
- **MIRROR**: Follow Spring's `Cache.get(key, Callable)` contract — the loader is only called on true cache miss.
- **IMPORTS**: `io.github.davidhlp.spring.cache.redis.register.RedisCacheRegister`, `io.github.davidhlp.spring.cache.redis.core.writer.support.protect.bloom.BloomSupport`.
- **GOTCHA**: The `key` parameter in `RedisProCache.get()` is of type `Object`, not `String`. Convert using `createCacheKey(key)` or the key serializer before passing to Bloom.
- **VALIDATE**: Integration test with `@RedisCacheable(useBloomFilter = true)`: when Bloom returns negative, assert the annotated method is **never invoked** (use an invocation counter bean).

#### Workflow & DoD
1. 🧪 【初步自测】
   - 开发者完成编码后，必须针对「交付物标准」进行本地功能自测或编写单元测试。
   
2. 🔍 【提交代码审查 (CR)】
   - 发起 Pull Request (PR) / Merge Request (MR)，邀请核心团队成员进行代码评审，并完整记录 CR 反馈意见。

3. 🛠️ 【修复 CR 意见 (Fix CR)】
   - 针对评审中发现的性能、安全、规范等问题逐项修复，禁止遗漏任何一条 blocking 意见。

4. 🔄 【回归测试】
   - 对修复后的代码再次进行本地回归测试，确保没有引入新的破坏性变更（Regression Bug）。

5. 🏁 【流程终止与提交条件】
   - **准出条件（DoD）**：只有当所有 CR 意见全部闭环、Reviewer 给出 Approved 状态，且回归测试 100% 通过时，方可执行最后一步。
   - **最终操作**：将代码正式 Commit 并合并至主分支，更新任务状态为“已完成”。

---

### TASK-005: [Core] Remove Read-Path Write Amplification
- **ACTION**: Remove `cachedValue.withAccessUpdate()` and `updateTtlIfExists()` from `ActualCacheHandler.processCacheHit()` so that cache reads do not trigger Redis writes.
- **IMPLEMENT**:
  1. In `ActualCacheHandler.processCacheHit()`, delete lines that call `withAccessUpdate()` and `updateTtlIfExists()`.
  2. Return the cached value directly without modification.
  3. If metrics are needed, keep `statistics.incHits()` but do not rewrite the value.
- **MIRROR**: Spring Data Redis `RedisCache.lookup()` is pure read with no write.
- **IMPORTS**: None new.
- **GOTCHA**: If there is existing code or tests that depend on `lastAccessTime` being updated in Redis, those tests will fail and must be updated.
- **VALIDATE**: Integration test: perform 100 cache GETs on the same key, assert via Redis `MONITOR` or command introspection that zero `SET` commands are issued.

#### Workflow & DoD
1. 🧪 【初步自测】
   - 开发者完成编码后，必须针对「交付物标准」进行本地功能自测或编写单元测试。
   
2. 🔍 【提交代码审查 (CR)】
   - 发起 Pull Request (PR) / Merge Request (MR)，邀请核心团队成员进行代码评审，并完整记录 CR 反馈意见。

3. 🛠️ 【修复 CR 意见 (Fix CR)】
   - 针对评审中发现的性能、安全、规范等问题逐项修复，禁止遗漏任何一条 blocking 意见。

4. 🔄 【回归测试】
   - 对修复后的代码再次进行本地回归测试，确保没有引入新的破坏性变更（Regression Bug）。

5. 🏁 【流程终止与提交条件】
   - **准出条件（DoD）**：只有当所有 CR 意见全部闭环、Reviewer 给出 Approved 状态，且回归测试 100% 通过时，方可执行最后一步。
   - **最终操作**：将代码正式 Commit 并合并至主分支，更新任务状态为“已完成”。

---

### TASK-006: [Core] Implement True sync=true Single-Flight in Writer
- **ACTION**: Override `RedisCacheWriter.get(String name, byte[] key, Supplier<byte[]> valueLoader, Duration ttl, boolean timeToIdleEnabled)` in `RedisProCacheWriter` to use distributed locking (Redisson) for single-flight loader invocation.
- **IMPLEMENT**:
  1. Add override method in `RedisProCacheWriter`.
  2. Look up `RedisCacheableOperation` metadata to check `sync` flag and `syncTimeout`.
  3. If `sync` is true, use `SyncSupport.executeSync(lockKey, () -> { ... }, timeout)` around the loader invocation.
  4. The inner logic should: check cache → if miss, invoke `valueLoader.get()` → put result → return result.
  5. If `sync` is false, delegate to the default `get` behavior (read-only, no lock).
- **MIRROR**: Follow `DefaultRedisCacheWriter` pattern for `get(name, key, valueLoader, ttl, tti)`.
- **IMPORTS**: `java.util.function.Supplier`, `org.springframework.data.redis.cache.RedisCacheWriter`.
- **GOTCHA**: The `valueLoader` returns `byte[]`, but ResiCache internally uses `CachedValue` wrapper. Ensure the loader result is wrapped before `put` and unwrapped before return.
- **VALIDATE**: Integration test: 10 threads concurrently request a cache miss with `sync=true`; assert the underlying `@Service` method is invoked exactly once.

#### Workflow & DoD
1. 🧪 【初步自测】
   - 开发者完成编码后，必须针对「交付物标准」进行本地功能自测或编写单元测试。
   
2. 🔍 【提交代码审查 (CR)】
   - 发起 Pull Request (PR) / Merge Request (MR)，邀请核心团队成员进行代码评审，并完整记录 CR 反馈意见。

3. 🛠️ 【修复 CR 意见 (Fix CR)】
   - 针对评审中发现的性能、安全、规范等问题逐项修复，禁止遗漏任何一条 blocking 意见。

4. 🔄 【回归测试】
   - 对修复后的代码再次进行本地回归测试，确保没有引入新的破坏性变更（Regression Bug）。

5. 🏁 【流程终止与提交条件】
   - **准出条件（DoD）**：只有当所有 CR 意见全部闭环、Reviewer 给出 Approved 状态，且回归测试 100% 通过时，方可执行最后一步。
   - **最终操作**：将代码正式 Commit 并合并至主分支，更新任务状态为“已完成”。

---

### TASK-007: [Configuration] Wire All Declared Properties into Runtime Behavior
- **ACTION**: Update `RedisProCacheConfiguration` and `RedisProCacheManager` so that every declared property in `RedisProCacheProperties` is actually applied.
- **IMPLEMENT**:
  1. `keyPrefix`: call `.computePrefixWith(...)` on `RedisCacheConfiguration`.
  2. `caches`: map `Map<String, CacheConfig>` to `Map<String, RedisCacheConfiguration>` and pass to `RedisCacheManager.builder().withInitialCacheConfigurations(...)`.
  3. `transactionAware`: pass to builder `.transactionAware()`.
  4. `disabledHandlers` / `handlerSettings`: pass to `CacheHandlerChainFactory` to conditionally exclude handlers from the chain.
  5. `serializer.failOnUnknownType` / `serializer.typeProperty`: pass to `SecureJackson2JsonRedisSerializer`.
  6. Add `@Validated` to `RedisProCacheProperties` and JSR-303 constraints (`@NotNull`, `@Min`, etc.) where appropriate.
- **MIRROR**: Spring Boot `@ConfigurationProperties` binding pattern.
- **IMPORTS**: `org.springframework.validation.annotation.Validated`, `jakarta.validation.constraints.NotNull`.
- **GOTCHA**: Changing from constructor-based `new RedisProCacheManager(...)` to builder-based `.builder(cacheWriter)` may change the inheritance structure; ensure `RedisProCacheManager` still creates `RedisProCache` instances.
- **VALIDATE**: Configuration binding tests for each property asserting that the runtime behavior reflects the configured value.

#### Workflow & DoD
1. 🧪 【初步自测】
   - 开发者完成编码后，必须针对「交付物标准」进行本地功能自测或编写单元测试。
   
2. 🔍 【提交代码审查 (CR)】
   - 发起 Pull Request (PR) / Merge Request (MR)，邀请核心团队成员进行代码评审，并完整记录 CR 反馈意见。

3. 🛠️ 【修复 CR 意见 (Fix CR)】
   - 针对评审中发现的性能、安全、规范等问题逐项修复，禁止遗漏任何一条 blocking 意见。

4. 🔄 【回归测试】
   - 对修复后的代码再次进行本地回归测试，确保没有引入新的破坏性变更（Regression Bug）。

5. 🏁 【流程终止与提交条件】
   - **准出条件（DoD）**：只有当所有 CR 意见全部闭环、Reviewer 给出 Approved 状态，且回归测试 100% 通过时，方可执行最后一步。
   - **最终操作**：将代码正式 Commit 并合并至主分支，更新任务状态为“已完成”。

---

### TASK-008: [Security] Secure Jackson Serializer — Default to Non-Polymorphic
- **ACTION**: Change `SecureJackson2JsonRedisSerializer` to disable Jackson default typing by default, and add explicit version envelope support for `CachedValue`.
- **IMPLEMENT**:
  1. Change default behavior: do not activate default typing unless `properties.getSerializer().isPolymorphicTypingEnabled()` is explicitly true.
  2. When polymorphic typing is enabled, use the configured `typeProperty` (default `@class`) instead of hardcoded.
  3. Add version envelope serialization for `CachedValue`:
     ```json
     {"version": 2, "payload": { ...actual cached value... }}
     ```
  4. On deserialization, check version; if unknown and `failOnUnknownType` is true, throw; if false, return miss.
- **MIRROR**: Follow OWASP deserialization guidance and Jackson `activateDefaultTyping` security best practices.
- **IMPORTS**: `com.fasterxml.jackson.databind.ObjectMapper`, `com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator`.
- **GOTCHA**: Changing serializer format is a breaking change for existing cached data. This must be clearly documented; consider a migration strategy or cache warm-clear on deploy.
- **VALIDATE**: Unit tests for (a) non-polymorphic round-trip, (b) polymorphic with allowed package, (c) unknown type with `failOnUnknownType=true` throws, (d) version mismatch handling.

#### Workflow & DoD
1. 🧪 【初步自测】
   - 开发者完成编码后，必须针对「交付物标准」进行本地功能自测或编写单元测试。
   
2. 🔍 【提交代码审查 (CR)】
   - 发起 Pull Request (PR) / Merge Request (MR)，邀请核心团队成员进行代码评审，并完整记录 CR 反馈意见。

3. 🛠️ 【修复 CR 意见 (Fix CR)】
   - 针对评审中发现的性能、安全、规范等问题逐项修复，禁止遗漏任何一条 blocking 意见。

4. 🔄 【回归测试】
   - 对修复后的代码再次进行本地回归测试，确保没有引入新的破坏性变更（Regression Bug）。

5. 🏁 【流程终止与提交条件】
   - **准出条件（DoD）**：只有当所有 CR 意见全部闭环、Reviewer 给出 Approved 状态，且回归测试 100% 通过时，方可执行最后一步。
   - **最终操作**：将代码正式 Commit 并合并至主分支，更新任务状态为“已完成”。

---

### TASK-009: [Dependency] Make Optional Dependencies Truly Optional
- **ACTION**: Use `ObjectProvider`, `@Autowired(required = false)`, and `@ConditionalOnClass` to ensure the starter boots without Redisson, Micrometer, and Actuator.
- **IMPLEMENT**:
  1. `RedisProCacheManager`: change constructor parameter `MeterRegistry meterRegistry` to `ObjectProvider<MeterRegistry> meterRegistryProvider`. If absent, use a no-op `MeterRegistry` (e.g., `new SimpleMeterRegistry()` or `MeterRegistry.NOOP`).
  2. `RedisProCacheConfiguration`: wrap Redisson-related beans in `@ConditionalOnClass(RedissonClient.class)`.
  3. `RedisProCacheConfiguration`: wrap Micrometer/Actuator-related beans in `@ConditionalOnClass(MeterRegistry.class)`.
  4. Ensure core beans (`RedisProCacheWriter`, `RedisProCacheManager`) have no hard dependency on optional classes.
- **MIRROR**: Spring Boot starter optional-dependency pattern (`spring-boot-autoconfigure` `@ConditionalOnClass`).
- **IMPORTS**: `org.springframework.beans.factory.ObjectProvider`, `org.springframework.boot.autoconfigure.condition.ConditionalOnClass`.
- **GOTCHA**: `RedisProCache` currently creates Micrometer timers/counters in constructor. If `MeterRegistry` is absent, these calls must be guarded or replaced with no-op metrics.
- **VALIDATE**: Create a minimal Spring Boot test project with ONLY `resicache-spring-boot-starter`, `spring-boot-starter-cache`, `spring-boot-starter-data-redis` dependencies; verify context loads successfully.

#### Workflow & DoD
1. 🧪 【初步自测】
   - 开发者完成编码后，必须针对「交付物标准」进行本地功能自测或编写单元测试。
   
2. 🔍 【提交代码审查 (CR)】
   - 发起 Pull Request (PR) / Merge Request (MR)，邀请核心团队成员进行代码评审，并完整记录 CR 反馈意见。

3. 🛠️ 【修复 CR 意见 (Fix CR)】
   - 针对评审中发现的性能、安全、规范等问题逐项修复，禁止遗漏任何一条 blocking 意见。

4. 🔄 【回归测试】
   - 对修复后的代码再次进行本地回归测试，确保没有引入新的破坏性变更（Regression Bug）。

5. 🏁 【流程终止与提交条件】
   - **准出条件（DoD）**：只有当所有 CR 意见全部闭环、Reviewer 给出 Approved 状态，且回归测试 100% 通过时，方可执行最后一步。
   - **最终操作**：将代码正式 Commit 并合并至主分支，更新任务状态为“已完成”。

---

### TASK-010: [Semantics] Rename Pre-Refresh to Early Expiration
- **ACTION**: Rename all `preRefresh` references in annotations, properties, handlers, and documentation to `earlyExpiration` to honestly reflect the actual behavior.
- **IMPLEMENT**:
  1. In `RedisCacheable` annotation: rename `enablePreRefresh` → `enableEarlyExpiration`, `preRefreshThreshold` → `earlyExpirationThreshold`, `preRefreshMode` → `earlyExpirationMode`.
  2. In `RedisCacheableOperation` and builder: rename corresponding fields.
  3. In `RedisProCacheProperties`: rename `PreRefreshProperties` → `EarlyExpirationProperties`, update YAML mapping.
  4. In `PreRefreshHandler`: rename class to `EarlyExpirationHandler`, update all log messages.
  5. In `PreRefreshSupport`: rename to `EarlyExpirationSupport`.
  6. Update all tests and documentation.
- **MIRROR**: Keep backward compatibility for one minor release by supporting both old and new property names with `@Deprecated`.
- **IMPORTS**: None new.
- **GOTCHA**: This is a breaking API change. The annotation attribute names change, requiring users to update their code. Document clearly in release notes.
- **VALIDATE**: Full-text search for `preRefresh` / `pre-refresh` / `PreRefresh` returns zero matches in `src/main/java`.

#### Workflow & DoD
1. 🧪 【初步自测】
   - 开发者完成编码后，必须针对「交付物标准」进行本地功能自测或编写单元测试。
   
2. 🔍 【提交代码审查 (CR)】
   - 发起 Pull Request (PR) / Merge Request (MR)，邀请核心团队成员进行代码评审，并完整记录 CR 反馈意见。

3. 🛠️ 【修复 CR 意见 (Fix CR)】
   - 针对评审中发现的性能、安全、规范等问题逐项修复，禁止遗漏任何一条 blocking 意见。

4. 🔄 【回归测试】
   - 对修复后的代码再次进行本地回归测试，确保没有引入新的破坏性变更（Regression Bug）。

5. 🏁 【流程终止与提交条件】
   - **准出条件（DoD）**：只有当所有 CR 意见全部闭环、Reviewer 给出 Approved 状态，且回归测试 100% 通过时，方可执行最后一步。
   - **最终操作**：将代码正式 Commit 并合并至主分支，更新任务状态为“已完成”。

---

### TASK-011: [Core] Fix Bloom Filter False-Positive Pollution
- **ACTION**: Remove the `bloomSupport.add()` call in `BloomFilterHandler.handleGetPostProcessing()` when Bloom positive but cache miss occurs.
- **IMPLEMENT**:
  1. Delete or comment out the `if (!result.isHit()) { bloomSupport.add(...); }` block.
  2. Keep `addToBloomFilter` on PUT/PUT_IF_ABSENT success (this is correct).
  3. Update class Javadoc to clarify that Bloom only adds keys on confirmed data existence (successful PUT).
- **MIRROR**: Bloom filter theory — add only on confirmed set membership.
- **IMPORTS**: None new.
- **GOTCHA**: If there are tests that assert Bloom grows after a GET miss, those tests must be updated or deleted.
- **VALIDATE**: Integration test: (a) PUT a key → assert Bloom contains it; (b) let cache expire → GET miss → assert Bloom still does NOT contain it (because it was never re-added after miss).

#### Workflow & DoD
1. 🧪 【初步自测】
   - 开发者完成编码后，必须针对「交付物标准」进行本地功能自测或编写单元测试。
   
2. 🔍 【提交代码审查 (CR)】
   - 发起 Pull Request (PR) / Merge Request (MR)，邀请核心团队成员进行代码评审，并完整记录 CR 反馈意见。

3. 🛠️ 【修复 CR 意见 (Fix CR)】
   - 针对评审中发现的性能、安全、规范等问题逐项修复，禁止遗漏任何一条 blocking 意见。

4. 🔄 【回归测试】
   - 对修复后的代码再次进行本地回归测试，确保没有引入新的破坏性变更（Regression Bug）。

5. 🏁 【流程终止与提交条件】
   - **准出条件（DoD）**：只有当所有 CR 意见全部闭环、Reviewer 给出 Approved 状态，且回归测试 100% 通过时，方可执行最后一步。
   - **最终操作**：将代码正式 Commit 并合并至主分支，更新任务状态为“已完成”。

---

### TASK-012: [Metrics] Deduplicate Cache Statistics
- **ACTION**: Choose a single statistics source and remove duplication between `RedisProCache` (Micrometer) and `ActualCacheHandler` (Spring Data Redis `CacheStatisticsCollector`).
- **IMPLEMENT**:
  1. Remove `statistics.incHits()`, `statistics.incMisses()`, `statistics.incPuts()`, `statistics.incDeletes()` calls from `ActualCacheHandler`.
  2. Keep only Micrometer metrics in `RedisProCache`.
  3. Alternatively, if team prefers Spring Data Redis native stats, remove Micrometer from `RedisProCache` and keep only `CacheStatisticsCollector`.
  3. Document the chosen standard in `CLAUDE.md`.
- **MIRROR**: Single-responsibility for metrics collection.
- **IMPORTS**: None new; remove `CacheStatisticsCollector` from `ActualCacheHandler` if choosing Micrometer.
- **GOTCHA**: If removing `CacheStatisticsCollector`, ensure `RedisProCacheWriter.withStatisticsCollector()` still returns a valid writer instance.
- **VALIDATE**: Integration test performing 1 hit and 1 miss; assert Micrometer counters reflect exactly 1 hit and 1 miss, with no double-counting.

#### Workflow & DoD
1. 🧪 【初步自测】
   - 开发者完成编码后，必须针对「交付物标准」进行本地功能自测或编写单元测试。
   
2. 🔍 【提交代码审查 (CR)】
   - 发起 Pull Request (PR) / Merge Request (MR)，邀请核心团队成员进行代码评审，并完整记录 CR 反馈意见。

3. 🛠️ 【修复 CR 意见 (Fix CR)】
   - 针对评审中发现的性能、安全、规范等问题逐项修复，禁止遗漏任何一条 blocking 意见。

4. 🔄 【回归测试】
   - 对修复后的代码再次进行本地回归测试，确保没有引入新的破坏性变更（Regression Bug）。

5. 🏁 【流程终止与提交条件】
   - **准出条件（DoD）**：只有当所有 CR 意见全部闭环、Reviewer 给出 Approved 状态，且回归测试 100% 通过时，方可执行最后一步。
   - **最终操作**：将代码正式 Commit 并合并至主分支，更新任务状态为“已完成”。

---

### TASK-013: [Integration] Enable Native Spring Cache Annotation Support
- **ACTION**: Implement `addSpringNativeCacheOperations()` in `RedisCacheOperationSource` so that `@Cacheable`, `@CachePut`, `@CacheEvict` are converted to ResiCache operations with global defaults.
- **IMPLEMENT**:
  1. When `AnnotatedElementUtils.findMergedAnnotation(..., Cacheable.class)` is found, create a `RedisCacheableOperation` with default ResiCache settings (global TTL, Bloom disabled unless globally enabled, etc.).
  2. Do the same for `@CachePut` → `RedisCachePutOperation`, `@CacheEvict` → `RedisCacheEvictOperation`.
  3. The converted operations should inherit `cacheNames`, `key`, `condition`, `unless` from the Spring annotation.
  4. Add `resi-cache.native-annotation-mode` property: `FULL` (convert all), `NONE` (ignore native), `SELECTIVE` (only when `@RedisCacheable` is also present).
- **MIRROR**: Spring's `AnnotationCacheOperationSource` handles native annotations; ResiCache's subclass should forward them.
- **IMPORTS**: `org.springframework.cache.annotation.Cacheable`, `org.springframework.cache.annotation.CachePut`, `org.springframework.cache.annotation.CacheEvict`.
- **GOTCHA**: Do not duplicate Spring's parsing logic. Use the parsed Spring `CacheableOperation` as a base and copy its fields into `RedisCacheableOperation.Builder`.
- **VALIDATE**: Integration test: annotate a method with Spring `@Cacheable("users")` (not `@RedisCacheable`), call the method, assert it uses `RedisProCacheWriter` and `RedisProCache`.

#### Workflow & DoD
1. 🧪 【初步自测】
   - 开发者完成编码后，必须针对「交付物标准」进行本地功能自测或编写单元测试。
   
2. 🔍 【提交代码审查 (CR)】
   - 发起 Pull Request (PR) / Merge Request (MR)，邀请核心团队成员进行代码评审，并完整记录 CR 反馈意见。

3. 🛠️ 【修复 CR 意见 (Fix CR)】
   - 针对评审中发现的性能、安全、规范等问题逐项修复，禁止遗漏任何一条 blocking 意见。

4. 🔄 【回归测试】
   - 对修复后的代码再次进行本地回归测试，确保没有引入新的破坏性变更（Regression Bug）。

5. 🏁 【流程终止与提交条件】
   - **准出条件（DoD）**：只有当所有 CR 意见全部闭环、Reviewer 给出 Approved 状态，且回归测试 100% 通过时，方可执行最后一步。
   - **最终操作**：将代码正式 Commit 并合并至主分支，更新任务状态为“已完成”。

---

### TASK-014: [Documentation] Declare Reactive/Async Support Limitations
- **ACTION**: Add explicit documentation and runtime guards for Reactive Streams (`Mono`/`Flux`) return types.
- **IMPLEMENT**:
  1. In `RedisCacheInterceptor` or `RedisCacheOperationSource`, detect `Mono`/`Flux` return types.
  2. If detected and `@RedisCacheable` is present, log a warning: "Reactive return types are not fully supported by ResiCache; falling back to Spring native caching."
  3. Update README to state: "ResiCache currently supports synchronous caching only. Reactive return types (Mono/Flux) will use Spring's native cache behavior without ResiCache enhancements."
  4. Update `RedisProCacheWriter.retrieve()` to use a dedicated executor instead of `ForkJoinPool.commonPool()` (or at least document this limitation).
- **MIRROR**: Honest feature documentation.
- **IMPORTS**: `reactor.core.publisher.Mono`, `reactor.core.publisher.Flux`.
- **GOTCHA**: Do not throw exceptions for Reactive types — that would break existing Spring Cache users. Just log and gracefully degrade.
- **VALIDATE**: Integration test with a method returning `Mono<String>` annotated with `@RedisCacheable`; assert it completes successfully and logs the expected warning.

#### Workflow & DoD
1. 🧪 【初步自测】
   - 开发者完成编码后，必须针对「交付物标准」进行本地功能自测或编写单元测试。
   
2. 🔍 【提交代码审查 (CR)】
   - 发起 Pull Request (PR) / Merge Request (MR)，邀请核心团队成员进行代码评审，并完整记录 CR 反馈意见。

3. 🛠️ 【修复 CR 意见 (Fix CR)】
   - 针对评审中发现的性能、安全、规范等问题逐项修复，禁止遗漏任何一条 blocking 意见。

4. 🔄 【回归测试】
   - 对修复后的代码再次进行本地回归测试，确保没有引入新的破坏性变更（Regression Bug）。

5. 🏁 【流程终止与提交条件】
   - **准出条件（DoD）**：只有当所有 CR 意见全部闭环、Reviewer 给出 Approved 状态，且回归测试 100% 通过时，方可执行最后一步。
   - **最终操作**：将代码正式 Commit 并合并至主分支，更新任务状态为“已完成”。

---

### TASK-015: [Infrastructure] Upgrade Spring Boot Baseline to 3.4.x
- **ACTION**: Update `pom.xml` parent to Spring Boot 3.4.x (latest stable), verify compatibility, and define a compatibility matrix.
- **IMPLEMENT**:
  1. Change `<version>` in `spring-boot-starter-parent` to `3.4.5` (or latest stable 3.4.x at time of implementation).
  2. Run `./mvnw dependency:tree` and resolve any dependency conflicts.
  3. Run full test suite; fix any API changes (e.g., `RedisCacheWriter` interface changes between 3.2 and 3.4).
  4. Update `CLAUDE.md` tech stack table to reflect new versions.
  5. Add `COMPATIBILITY.md` documenting: Java 17+, Spring Boot 3.4.x, Spring Data Redis 3.5.x, Redis 6.2+/7.x, Redisson 3.27+.
- **MIRROR**: Standard Maven dependency upgrade.
- **IMPORTS**: N/A — POM-only change.
- **GOTCHA**: Spring Data Redis 3.4+ may have changed `RedisCacheWriter` default methods or `RedisCacheConfiguration` builder methods. Compile errors must be fixed.
- **VALIDATE**: `./mvnw clean verify -B` passes with zero test failures on the new Boot version.

#### Workflow & DoD
1. 🧪 【初步自测】
   - 开发者完成编码后，必须针对「交付物标准」进行本地功能自测或编写单元测试。
   
2. 🔍 【提交代码审查 (CR)】
   - 发起 Pull Request (PR) / Merge Request (MR)，邀请核心团队成员进行代码评审，并完整记录 CR 反馈意见。

3. 🛠️ 【修复 CR 意见 (Fix CR)】
   - 针对评审中发现的性能、安全、规范等问题逐项修复，禁止遗漏任何一条 blocking 意见。

4. 🔄 【回归测试】
   - 对修复后的代码再次进行本地回归测试，确保没有引入新的破坏性变更（Regression Bug）。

5. 🏁 【流程终止与提交条件】
   - **准出条件（DoD）**：只有当所有 CR 意见全部闭环、Reviewer 给出 Approved 状态，且回归测试 100% 通过时，方可执行最后一步。
   - **最终操作**：将代码正式 Commit 并合并至主分支，更新任务状态为“已完成”。

---

### TASK-016: [Testing] Add Key-Resolution Compatibility Integration Test
- **ACTION**: Create a comprehensive integration test that asserts ResiCache's metadata lookup key matches Spring's actual cache key for all common key scenarios.
- **IMPLEMENT**:
  1. Create `KeyResolutionIntegrationTest` extending `AbstractRedisIntegrationTest`.
  2. Test cases:
     - `@RedisCacheable(key = "#id")` with method parameter `Long id`
     - `@RedisCacheable(key = "#root.methodName + ':' + #id")`
     - `@RedisCacheable(keyGenerator = "customKeyGenerator")`
     - Class-level annotation with method-level override
     - Multiple cache names
     - Composed annotation (`@MyCaching` meta-annotated with `@RedisCacheable`)
  3. For each case, call the annotated method and assert that `RedisCacheRegister` contains the metadata for that operation (verified by inspecting the register or by asserting that ResiCache features like random TTL were applied).
- **MIRROR**: Follow existing integration test patterns (`AbstractRedisIntegrationTest`, Testcontainers).
- **IMPORTS**: `org.springframework.cache.interceptor.KeyGenerator`, `org.springframework.cache.annotation.Cacheable`.
- **GOTCHA**: The test must not depend on internal implementation details that may change; use public API or behavior assertions (e.g., "random TTL was applied" implies metadata was found).
- **VALIDATE**: Test passes and covers at least 6 distinct key resolution scenarios.

#### Workflow & DoD
1. 🧪 【初步自测】
   - 开发者完成编码后，必须针对「交付物标准」进行本地功能自测或编写单元测试。
   
2. 🔍 【提交代码审查 (CR)】
   - 发起 Pull Request (PR) / Merge Request (MR)，邀请核心团队成员进行代码评审，并完整记录 CR 反馈意见。

3. 🛠️ 【修复 CR 意见 (Fix CR)】
   - 针对评审中发现的性能、安全、规范等问题逐项修复，禁止遗漏任何一条 blocking 意见。

4. 🔄 【回归测试】
   - 对修复后的代码再次进行本地回归测试，确保没有引入新的破坏性变更（Regression Bug）。

5. 🏁 【流程终止与提交条件】
   - **准出条件（DoD）**：只有当所有 CR 意见全部闭环、Reviewer 给出 Approved 状态，且回归测试 100% 通过时，方可执行最后一步。
   - **最终操作**：将代码正式 Commit 并合并至主分支，更新任务状态为“已完成”。

---

### TASK-017: [Testing] Add Concurrency Single-Flight Integration Test
- **ACTION**: Create an integration test that proves `sync=true` prevents concurrent method invocations under cache miss.
- **IMPLEMENT**:
  1. Create `SyncSingleFlightIntegrationTest`.
  2. Define a `@Service` with a method annotated `@RedisCacheable(value = "test", sync = true)`.
  3. The method should have an `AtomicInteger` counter that increments on each invocation and sleep for 100ms to simulate slow loading.
  4. Use `ExecutorService` with 10 threads to concurrently call the method with the same argument.
  5. Assert that the counter value is exactly 1 after all threads complete.
  6. Assert that all threads received the same cached result.
- **MIRROR**: Concurrency testing with `CountDownLatch` or `ExecutorService.invokeAll`.
- **IMPORTS**: `java.util.concurrent.*`, `java.util.concurrent.atomic.AtomicInteger`.
- **GOTCHA**: The test must clean up the cache before and after to ensure a true miss scenario. Use `@BeforeEach` to evict the test key.
- **VALIDATE**: Test passes consistently (run 5 times to check for flakiness).

#### Workflow & DoD
1. 🧪 【初步自测】
   - 开发者完成编码后，必须针对「交付物标准」进行本地功能自测或编写单元测试。
   
2. 🔍 【提交代码审查 (CR)】
   - 发起 Pull Request (PR) / Merge Request (MR)，邀请核心团队成员进行代码评审，并完整记录 CR 反馈意见。

3. 🛠️ 【修复 CR 意见 (Fix CR)】
   - 针对评审中发现的性能、安全、规范等问题逐项修复，禁止遗漏任何一条 blocking 意见。

4. 🔄 【回归测试】
   - 对修复后的代码再次进行本地回归测试，确保没有引入新的破坏性变更（Regression Bug）。

5. 🏁 【流程终止与提交条件】
   - **准出条件（DoD）**：只有当所有 CR 意见全部闭环、Reviewer 给出 Approved 状态，且回归测试 100% 通过时，方可执行最后一步。
   - **最终操作**：将代码正式 Commit 并合并至主分支，更新任务状态为“已完成”。

---

## Testing Strategy

### Unit Tests

| Test | Input | Expected Output | Edge Case? |
|---|---|---|---|
| `RedisCacheRegisterTest.findByAnnotatedElementKey` | `new AnnotatedElementKey(method, clazz)` | Returns correct `RedisCacheableOperation` | Multiple cache names |
| `SecureJackson2JsonRedisSerializerTest.roundTripNonPolymorphic` | Plain POJO | Same POJO after ser/de | Null value |
| `SecureJackson2JsonRedisSerializerTest.rejectUnknownType` | Unknown class JSON | Throws `SerializationException` | `failOnUnknownType=false` returns null |
| `BloomFilterHandlerTest.noAddOnMiss` | GET miss after Bloom positive | Bloom does NOT contain key | Key expired but exists in DB |
| `EarlyExpirationHandlerTest.syncModeReturnsMiss` | Cached value past threshold | Returns miss, triggers reload | Async mode shortens TTL |
| `RedisProCacheWriterTest.noWriteOnHit` | 100 GETs on existing key | Zero SET commands | TTL near expiration |

### Integration Tests

| Test | Scenario | Assertion |
|---|---|---|
| `KeyResolutionIntegrationTest` | Various SpEL/keyGenerator/composed annotations | Metadata lookup succeeds for all |
| `SyncSingleFlightIntegrationTest` | 10 threads, sync=true, cache miss | Method invoked exactly once |
| `BloomLoaderShortCircuitIntegrationTest` | Bloom negative, `@Cacheable` method | Method never invoked |
| `NativeAnnotationSupportIntegrationTest` | Spring `@Cacheable` without `@RedisCacheable` | Uses `RedisProCacheWriter` |
| `ConfigurationBindingIntegrationTest` | `application.yml` with all properties | Each property reflected in runtime beans |

### Edge Cases Checklist
- [ ] Empty cache name list (validation should reject)
- [ ] `key` and `keyGenerator` both set (validation should reject)
- [ ] Cache miss with `sync=true` and lock timeout (define fallback behavior)
- [ ] Bloom backend failure (should default to `mightContain=true` — current behavior is correct)
- [ ] Serialization failure during GET (should return miss, not throw)
- [ ] Concurrent PUT and evict (ensure no stale reads)
- [ ] Redis unavailable (define fail-open vs fail-closed policy)

---

## Validation Commands

### Static Analysis
```bash
./mvnw checkstyle:check
```
EXPECT: Zero checkstyle violations

### Unit Tests
```bash
./mvnw test -q
```
EXPECT: `Tests run: N, Failures: 0, Errors: 0, Skipped: 0`

### Full Test Suite
```bash
./mvnw clean verify -B
```
EXPECT: Build success, JaCoCo coverage ≥ 80% line coverage

### Dependency Validation
```bash
./mvnw dependency:tree | grep -E "(redisson|micrometer|actuator)"
```
EXPECT: Optional dependencies correctly scoped; no hard transitive requirement on optional libs

### Manual Validation
- [ ] Start minimal Spring Boot app with only core dependencies; context loads
- [ ] Start app without Redisson; sync fallback to standard Spring behavior
- [ ] Start app without Micrometer; no `MeterRegistry` bean; cache works
- [ ] Annotate method with `@Cacheable` (Spring native); verify it uses `RedisProCache`
- [ ] Run `redis-cli MONITOR`; perform 10 cache hits; assert no SET commands

---

## Acceptance Criteria
- [ ] All 17 tasks completed
- [ ] All validation commands pass
- [ ] Tests written and passing for every P0 and P1 task
- [ ] No checkstyle violations
- [ ] JaCoCo line coverage ≥ 80%
- [ ] No hard dependency on optional libraries (verified by minimal-app test)
- [ ] Documentation updated: README, CLAUDE.md, COMPATIBILITY.md

## Completion Checklist
- [ ] Code follows discovered patterns (Lombok, `@Component`, `@HandlerPriority`)
- [ ] Error handling matches codebase style (delegate to `CacheErrorHandler`)
- [ ] Logging follows codebase conventions (SLF4J parameterized, Chinese Javadoc)
- [ ] Tests follow test patterns (mirror source package, extend `AbstractRedisIntegrationTest`)
- [ ] No hardcoded values (use `RedisProCacheProperties`)
- [ ] Documentation updated
- [ ] No unnecessary scope additions
- [ ] Self-contained — no questions needed during implementation

## Risks
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Spring Boot 3.4 upgrade breaks RedisCacheWriter API | Medium | High | Pin to latest 3.4 patch; fix compile errors incrementally |
| Removing read-path writes breaks existing users relying on `lastAccessTime` | Medium | Medium | Document breaking change; consider opt-in flag for TTI if strongly requested |
| AnnotatedElementKey lookup causes memory pressure | Low | Medium | Register uses bounded eviction strategy (already implemented via `EvictionStrategy`) |
| Bloom filter move to loader layer changes behavior for existing users | High | Medium | Document as intentional semantic fix; users who relied on old behavior need to review |
| Sync lock redesign introduces deadlocks | Low | High | Use lock timeout with fallback; extensive concurrency testing |

## Notes
- This plan intentionally does NOT implement true refresh-ahead, L1/L2 caching, or multi-artifact splitting. Those are P3 features to be planned separately after P0–P2 stabilization.
- The `AnnotatedElementKey`-based metadata lookup is the foundational change that unblocks TASK-003, TASK-004, and TASK-006. It should be implemented first among P0 tasks.
- The test suite fix (TASK-001) is a prerequisite for all other tasks because CI must be green before merging anything.
