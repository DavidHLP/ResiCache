# Codebase Concerns

**Analysis Date:** 2026-04-24

## Tech Debt

**[RedisProCache.size() Counter Inaccuracy]**
- Issue: The `size` field in `RedisProCache` only increments on `put()` (line 68 in `RedisProCache.java`) but never decrements on `evict()`. The `evict()` method does not call `size.decrementAndGet()`.
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/core/RedisProCache.java`
- Impact: `getSize()` returns an inflated count. Actual Redis size may differ significantly from reported size after evictions.
- Fix approach: Decrement size on evict, or track actual Redis size instead of maintaining a counter.

**[TwoListLRU getActiveSize/getInactiveSize Read Lock Contention]**
- Issue: `getActiveSize()` and `getInactiveSize()` acquire read locks on every call. Under high concurrency with many size checks, this creates lock contention.
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/strategy/eviction/support/TwoListLRU.java` (lines 225-246)
- Impact: Performance degradation under read-heavy workloads.
- Fix approach: Use atomic counters or StripedReadWriteLock to reduce contention.

**[RedisProCacheWriter.getChain() Double-Checked Locking Pattern]**
- Issue: The `cachedChain` field (line 47) is `volatile`, and the double-checked locking pattern is technically correct with volatile, but the synchronization block (lines 304-312) could be simplified.
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/RedisProCacheWriter.java` (lines 47, 303-313)
- Impact: Low risk - volatile makes DCL correct in Java 5+. However, the pattern is confusing and could be simplified.
- Fix approach: Consider using synchronized only or a more idiomatic lazy initialization pattern.

**[CachedValue TTL Expiry Check Potential Overflow]**
- Issue: `isExpired()` in `CachedValue` uses `System.nanoTime()` for relative time calculation, but the TTL comparison at line 86 converts nanoseconds to milliseconds with integer division: `elapsedMs >= ttl * 1000`. For TTLs > ~2.1 million seconds, this could overflow.
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/CachedValue.java` (lines 73-92)
- Impact: Theoretical only - overflow would only occur for TTLs > ~24 days when using nanosecond precision. Default TTL is 30 minutes.
- Fix approach: Use `TimeUnit.SECONDS.toMillis(ttl)` to avoid overflow.

## Known Bugs

**[RedisProCache.evict() Does Not Decrement Size]**
- Symptoms: `getSize()` returns values higher than actual cache entries after evictions occur.
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/core/RedisProCache.java` (lines 74-82)
- Trigger: Call `evict()` on any cache entry, then check `getSize()`.
- Workaround: Use Redis `DBSIZE` command to get actual key count.

**[ThreadPoolPreRefreshExecutor.cleanFinished() Only Runs on getActiveCount()]**
- Symptoms: `getActiveCount()` may return counts that include completed but not-yet-removed futures.
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/support/refresh/ThreadPoolPreRefreshExecutor.java` (lines 262-268)
- Trigger: High pre-refresh task throughput - completed futures accumulate until next `getActiveCount()` call.
- Workaround: Call `getActiveCount()` periodically to trigger cleanup.

## Security Considerations

**[SecureJackson2JsonRedisSerializer Package Whitelist Strictness]**
- Risk: Default allowed package is only `io.github.davidhlp`. If users store objects from other packages, deserialization will fail silently.
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/config/SecureJackson2JsonRedisSerializer.java` (line 41)
- Current mitigation: Whitelist-based PolymorphicTypeValidator prevents arbitrary class deserialization (addresses Jackson deserialization vulnerability).
- Recommendations: Document the package whitelist requirement clearly. Users must configure `resi-cache.serializer.allowed-package-prefixes` if storing domain objects from other packages.

**[SpelConditionEvaluator Reflection Access]**
- Risk: Uses reflection to access the `unless` field via `operation.getClass().getDeclaredField("unless")` (line 97). This bypasses normal field access and could break if Spring internal implementation changes.
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/core/evaluator/SpelConditionEvaluator.java` (lines 94-105)
- Current mitigation: Try-catch with fallback to empty string.
- Recommendations: Request the `unless` field be exposed via an interface or method on Spring's `CacheOperation`.

**[Bloom Filter Redis Operations Without Explicit Timeout]**
- Risk: Bloom filter `add()` and `mightContain()` use `executePipelined()` without explicit timeout. Redis blocking operations could hang indefinitely.
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/support/protect/bloom/filter/RedisBloomIFilter.java` (lines 40-48, 75-80)
- Current mitigation: Uses Redis HSET/HGET which are typically fast.
- Recommendations: Configure RedisTemplate with timeout values.

## Performance Bottlenecks

**[TwoListLRU Write Lock Contention on put()]**
- Problem: Every `put()` and `get()` with promotion acquires a write lock due to list modifications.
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/strategy/eviction/support/TwoListLRU.java` (lines 102-139, 148-161)
- Cause: Doubly-linked list with sentinel nodes requires write lock for any modification.
- Improvement path: Consider lock-free data structure or striped locking for higher concurrency.

**[Bloom Filter Hash Function Recalculation]**
- Problem: `mightContain()` recalculates hash positions every call (line 68). `add()` also recalculates (line 37).
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/support/protect/bloom/filter/RedisBloomIFilter.java`
- Cause: No caching of computed hash positions per key.
- Improvement path: Cache computed positions in a local concurrent map for frequently-accessed keys.

**[ConcurrentHashMap inFlight Growth Without Automatic Cleanup]**
- Problem: `ThreadPoolPreRefreshExecutor.inFlight` is a `ConcurrentHashMap` that only removes entries on completion. Under memory pressure, this map could grow indefinitely with stale entries if `cleanFinished()` is not called.
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/support/refresh/ThreadPoolPreRefreshExecutor.java` (line 45)
- Cause: `cleanFinished()` only runs during `getActiveCount()` calls.
- Improvement path: Add a scheduled cleanup task or use a WeakHashMap variant.

**[PreRefreshHandler Pre-refresh Async TTL Race]**
- Problem: Pre-refresh tasks scheduled with `CompletableFuture.runAsync()` do not inherit the calling context's timeout. If the thread pool is saturated, pre-refresh tasks may run after the cached data has already expired.
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PreRefreshHandler.java`
- Cause: No deadline tracking for pre-refresh tasks.
- Improvement path: Add TTL-aware pre-refresh that checks expiry before populating cache.

## Fragile Areas

**[CacheHandlerChain Handler Ordering Criticality]**
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/CacheHandlerChain.java`
- Why fragile: Handler chain order is critical. If BloomFilterHandler runs after ActualCacheHandler, the bloom filter will not prevent cache penetration on writes. The `@HandlerPriority` annotations must be carefully maintained.
- Safe modification: When adding new handlers, ensure priority values do not conflict with existing handlers (defined in `HandlerOrder`).
- Test coverage: Handler chain ordering is implicitly tested but could benefit from explicit integration tests.

**[SpEL Expression Caching No Invalidation]**
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/core/evaluator/SpelConditionEvaluator.java` (line 34)
- Why fragile: Expression cache uses `ConcurrentHashMap` with expression strings as keys. If annotation values change at runtime (rare), cache is not invalidated.
- Safe modification: The cache is bounded by method count, so memory is bounded by method count using SpEL conditions.

**[Redis Key Pattern Assumptions in extractActualKey()]**
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/RedisProCacheWriter.java` (lines 277-283)
- Why fragile: Assumes all Redis keys follow `{cacheName}::` prefix pattern. If keys are manually created with different formats, `extractActualKey()` returns the full key.
- Safe modification: The method is defensive - if prefix does not match, returns original key.

## Scaling Limits

**[ThreadPoolPreRefreshExecutor Fixed Pool]**
- Current capacity: 2 core threads, 10 max, queue capacity 100.
- Limit: At max capacity with 100 queued tasks, new tasks use CallerRunsPolicy (runs in caller thread, blocking).
- Scaling path: Make pool size configurable via `RedisProCacheProperties.preRefresh.poolSize` and `preRefresh.maxPoolSize`.

**[Bloom Filter Single Hash Per Cache]**
- Current capacity: Each cache has one Redis Hash for the bloom filter (`bloomKey = config.getKeyPrefix() + cacheName`).
- Limit: Hash grows with unique keys. For caches with billions of unique keys, the Hash could become memory-constrained.
- Scaling path: Implement bloom filter sharding across multiple Redis Hashes or use RedisBloom module (separate infrastructure).

**[TwoListLRU Memory Usage]**
- Current capacity: `ConcurrentHashMap<K, Node<K, V>>` stores all cached entries in memory for LRU tracking.
- Limit: For caches with millions of entries, the node map could consume significant heap.
- Scaling path: Consider offloading LRU tracking to Redis with sorted sets, or use a sampling-based LRU approximation.

## Dependencies at Risk

**[Redisson 3.27.0 Heavy Dependency]**
- Risk: Heavy dependency (Redisson) for distributed locking. Single point of failure if Redisson has bugs.
- Impact: Cache locking (cache stampede prevention) depends on Redisson's distributed lock implementation.
- Migration plan: `LockProvider` is an SPI - can implement alternative lock providers (e.g., RedisTemplate-based locks) if needed.

**[Hutool JSON 5.8.25]**
- Risk: Using Hutool for JSON only (`hutool-json`). Adds another library to audit.
- Impact: Limited - only used for JSON utilities if any.
- Migration plan: Remove if not actively used; rely on Jackson only.

**[Spring Boot 3.2.4 Version Coupling]**
- Risk: Tied to Spring Boot for auto-configuration and Spring Cache integration.
- Impact: Version compatibility critical. Spring Boot 3.x changes could break integration.
- Migration plan: Maintain version compatibility matrix in documentation.

## Missing Critical Features

**[No Cache Statistics Reset via JMX/Actuator]**
- Problem: `RedisCacheManager.clearStatistics()` exists but there is no exposed endpoint to trigger it.
- Blocks: Operational teams cannot reset statistics without code changes.
- Recommendation: Add Actuator endpoint for cache statistics management.

**[No Cache Entry Listener/Event System]**
- Problem: No hooks for cache hit/miss/eviction events beyond Micrometer metrics.
- Blocks: Custom logging, alerting, or auditing on cache events.
- Recommendation: Add Spring's `CacheEvent` publishing mechanism.

**[No Distributed Cache Support Beyond Redis]**
- Problem: All caching infrastructure assumes Redis.
- Blocks: Multi-level caching (local + distributed) or hybrid caching patterns.
- Recommendation: Consider adding memory-first caching with `Caffeine` as L1 cache.

## Test Coverage Gaps

**[Untested: TwoListLRU under concurrent access]**
- What's not tested: `TwoListLRU` is tested with sequential operations, but concurrent access patterns (simultaneous put/get from multiple threads) are not verified.
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/strategy/eviction/support/TwoListLRU.java`
- Risk: Concurrent modifications could cause list corruption or deadlock with `ReentrantReadWriteLock`.
- Priority: High

**[Untested: Handler chain error scenarios]**
- What's not tested: How the handler chain behaves when a handler throws an exception mid-chain. Does `CacheErrorHandler` properly recover?
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/CacheHandlerChain.java`
- Risk: Unhandled exceptions could leave cache in inconsistent state.
- Priority: Medium

**[Untested: Bloom filter false positive impact]**
- What's not tested: When bloom filter returns false positive (key does not exist but filter says it might), the actual Redis lookup is still performed. The actual cache penetration protection ratio is not measured.
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/BloomFilterHandler.java`
- Risk: Bloom filter configuration (expectedInsertions, falseProbability) may not match actual usage, leading to either excessive memory use or inadequate protection.
- Priority: Medium

**[Untested: PreRefreshHandler race conditions]**
- What's not tested: Race between pre-refresh population and explicit evict/put operations. Could pre-refresh overwrite a newer value?
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PreRefreshHandler.java`
- Risk: Stale data could overwrite fresh data if timing is unfortunate.
- Priority: High

**[Untested: SpEL expression injection via user input]**
- What's not tested: Can user-controlled method parameters be used in SpEL condition expressions to inject malicious expressions?
- Files: `src/main/java/io/github/davidhlp/spring/cache/redis/core/evaluator/SpelConditionEvaluator.java`
- Risk: If condition expressions are derived from user input, SpEL injection could occur. Currently expressions come from annotation constants, so low risk.
- Priority: Low

---

*Concerns audit: 2026-04-24*
