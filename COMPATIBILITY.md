# Compatibility Matrix

ResiCache ships on a **single build line**:

- **`main` branch — Spring Boot 4.0 / SDR 4.0 / Spring 7 / Java 21 /
  Redisson 3.50**.

CI is configured to run `clean verify -B` on Java 21. Local verification also
requires JDK 21 and Docker for Testcontainers; this session is not a release
baseline.

> **Historical context**: Previously the repository carried a `boot3` line
> (Boot 3.4.13 / Java 17 / Redisson 3.27). The migration to Boot 4 merged
> into the main line; the dual-branch strategy is **abandoned**. Boot 3.x
> compatibility is not maintained. See `CHANGELOG.md` for migration context.
>
> Verified 2026-09-05: every Maven Central version (0.0.1–0.0.5, 0.0.7,
> 3.2.4) is the earlier Boot 3.2.4 / Java 17 line; no Boot 4 artifact is
> published yet. A bounded public adopter search on the same date found no
> external consumers of any line (private adopters remain unprovable).

## Supported versions

### `main` line — Spring Boot 4.0 (sole line)

| Component | Version | Tested |
|-----------|---------|--------|
| Java | 21 | CI |
| Spring Boot | 4.0.0 | 4.0.x (CI) |
| Spring Framework | 7.x | (via Boot) |
| Spring Cache | 7.x | (via Boot) |
| Spring Data Redis | 4.0.x | (via Boot) |
| Redis Server | 7.x | 7.x |
| Redisson | 3.50.0 | 3.50.0 |
| Caffeine | 3.1.8 | 3.1.8 |

## Spring Boot version policy

- **`main` line (sole line)**: `spring-boot-starter-parent 4.0.0` + SDR 4.0
  + Spring 7 + Java 21 + Redisson 3.50. Build/verify with
  `./mvnw clean verify -B` on JDK 21.
- **Boot 4 modularization note**: Boot 4 relocated packages
  (`o.s.b.autoconfigure.data.redis.*` → `o.s.b.data.redis.autoconfigure.*` and
  `o.s.b.actuate.health.*` → `o.s.b.health.contributor.*`) and SDR 4 renamed
  `RedisCacheWriter` methods (`remove`→`evict`, `clean`→`clear`).
- **Not supported**: Spring Boot 2.x and 3.x. No multi-Boot compatibility line
  is maintained.
- **Pre-1.0 caveat**: matrix coverage is best-effort until 1.0.

## Optional dependencies

| Dependency | Required? | Notes |
|---|---|---|
| **Redisson** | Optional | Needed for distributed-lock (`sync=true`). Without it, a
  sync operation fails fast unless `resi-cache.sync-lock.local-only=true` is
  explicitly configured. |
| **Micrometer / Actuator** | Optional | Without a `MeterRegistry`, cache metrics
  are disabled. `RedisCacheHealthIndicator` requires Actuator. |
| **Caffeine** | Bundled | Used internally for the local hash cache and
  bloom-filter bitset; not exposed as a multi-level cache. |

## Serialization compatibility

⚠️ ResiCache serializes values in an internal `{version, payload}` envelope via
`SecureJackson` for safe deserialization. This is **not** wire-compatible with
Spring's `GenericJackson2JsonRedisSerializer` or `JdkSerializer`. Existing caches must be **migrated** when adopting ResiCache, otherwise the
entire cache misses on cutover. Adopt a bounded **shadow-read → dual-write →
cutover** migration workflow: run ResiCache alongside the existing cache,
shadow-read through the new serializer while dual-writing, then cut over once
hit rates stabilize. This preserves TTL, supports resumable rollback, and does
not require a cache flush.

## Known limitations

- **Reactive types**: `Mono<T>` / `Flux<T>` return types are **not supported**.
  ResiCache's interceptor is blocking; such methods log an explicit "caching
  will not take effect" warning and bypass ResiCache.
- **Async methods**: `@Async` cached methods are not supported for sync-lock and
  Bloom-filter enhancements.

- **Cache I/O failures**: GET returns a miss with internal failure metadata;
  PUT, PUT_IF_ABSENT, and CLEAN fail fast with a typed runtime failure retaining
  the original cause;
  REMOVE is observable best-effort.
- **Protection switch lifecycle**: `resi-cache.protection.*` is resolved once
  at chain creation (startup). `protection.enabled=false` disables
  bloom/sync-lock/early-expiration/null-value and keeps TTL/ActualCache; a
  per-mechanism `false` disables only that mechanism; a per-mechanism `true`
  cannot re-enable a mechanism when the total switch is `false`; changing
  protection configuration requires a restart (no runtime refresh).
- **Read-through write-back failures**: `get(key, loader)` is
  availability-first — a successful loader value is always returned; a
  write-back failure is logged (redacted, no raw key) and does not override
  the value. Loader failures surface as Spring `Cache.ValueRetrievalException`
  (type, cause, and loader identity preserved); exception text carries no raw
  key — the checked-exception wrapper names the cache only.
- **Bloom CLEAN semantics**: Bloom tracks possible data-source membership,
  not current cache entries. CLEAN preserves existing bits and never uses a
  rebuilding marker or TTL window; false-positives are safe, while loader
  execution must not be blocked by a Bloom false-negative.
- **User `CacheManager` opt-out**: defining your own `CacheManager` bean backs
  off the library's `RedisProCacheManager` and, with it, the ResiCache
  annotation proxy (`redisCacheAdvisor`/`redisCacheInterceptor`); your Spring
  Cache setup stays in charge and startup does not fail. Supplying your own
  `RedisProCacheManager` (a public class) keeps the library proxy active.
- **Transaction-aware caching**: supported, but requires explicit
  `resi-cache.transaction-aware=true`.
- **Redis Cluster distributed locks**: lock keys are **hash-tag pinned** to the
  same slot as the cache key, so the lock and the data it guards
  co-locate on one node. Validated by `RedisClusterSlotIntegrationTest` against a
  real three-master `redis:7` Cluster: the live Redisson lock key and cache key
  return the same `CLUSTER KEYSLOT`, and a two-key command completes without
  `CROSSSLOT`.
