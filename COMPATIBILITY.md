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
| Testcontainers | 1.20.6 (test scope) | CI/integration tests |

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
| **Micrometer / Actuator** | Optional | Cache metrics require
  `resi-cache.metrics.enabled=true` (default OFF) and a `MeterRegistry`;
  otherwise the resolved metrics seam is a no-op adapter.
  `RedisCacheHealthIndicator` requires Actuator and the `HealthIndicator`
  class; it is not gated on the metrics property. |
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
  (type and cause preserved; the diagnostic key is reduced to cache name);
  exception text carries no raw key — the checked-exception wrapper names the
  cache only.
- **Native writer time-to-idle reads**: `RedisCacheWriter.get(..., cacheTti)`
  intentionally ignores `cacheTti`; native reads do not refresh TTL because
  refresh-on-read would add write amplification. Use ordinary TTL semantics on
  this low-level SPI path.

- **Native writer statistics**: SDR 4.0 writer counters are now emitted by
  `RedisProCacheWriter` for GET/GET-hit/GET-miss/PUT/DELETE, including exact
  `clear` deletion counts and PUT_IF_ABSENT insertion. `withStatisticsCollector`
  fully rebinds statistics; lock-wait duration remains unreported (zero).
- **Class-level cache annotations**: Spring operation resolution sees class-level ResiCache annotations, but the annotation chain does not apply their policy fields to methods without method-level annotations; this behavior is unchanged from `main`.
- **`value` and `cacheNames` resolution**: the three annotations are not
  `@AliasFor`-linked, so one declaration may set both attributes. There is one
  resolution for both faces (`RedisCacheAttributesProjector.resolveCacheNames`)
  and **`value` wins**; `cacheNames` is the fallback and only applies when
  `value` is empty. A declaration that sets both targets the `value` cache.
  This is the operation face's `main` behaviour, so the cache in use is
  unchanged. The policy face changes for that same both-set declaration: on
  `main` the operation targeted `value` while the policy snapshot was
  registered under `cacheNames`, so the declared policy silently did not apply
  to the cache that was used. Both faces now resolve to `value`, and the policy
  applies to the cache in use.
- **TTL default precedence**: one module owns the resolution (`TtlPolicy`; the
  ordered rule is specified in [`docs/REFERENCE.md`](docs/REFERENCE.md)). A
  method-level `ttl` greater than zero is the only declaration that overrides
  the configured cache TTL. An annotated method that does not set `ttl` (the
  attribute's value is then `0`, i.e. no declaration) expires its entries
  after `resi-cache.default-ttl` (default `30m`, per-cache `caches.*.ttl`
  overrides it), exactly like a plain Spring `@Cacheable` in `SELECTIVE` mode.
  There is no second implicit TTL default: a zero or negative Duration
  parameter means an entry without expiry, and a `null` parameter is treated
  the same way — Spring Data Redis 4.0 expresses "no expiry" as
  `Duration.ZERO` (`RedisCacheConfiguration`'s default `TtlFunction` is
  `persistent()`, and `entryTtl` rejects `null`), so a write path never
  carries a `null` TTL.
- **Annotation TTL fallback (behaviour change)**: on the previous build line
  the `@RedisCacheable`/`@RedisCachePut` `ttl` attribute defaulted to `60`
  seconds, so an annotated method without an explicit `ttl` expired its
  entries after `60s` even when `resi-cache.default-ttl` was configured. The
  annotation-side implicit `60` is removed: such a method now uses the
  configured default (`30m` unless overridden), and `60` is no longer
  reachable from the resolution path. Methods that set `ttl` explicitly are
  unaffected. Deployments relying on the old `60s` expiry for methods that
  omit `ttl` must either set `ttl` explicitly or set `resi-cache.default-ttl`.
  A cache configured with no expiry (a caller-supplied
  `RedisCacheConfiguration`) stays without expiry instead of receiving a
  `60s` entry lifetime.
- **Refresh metadata**: the version-2 envelope persists the fields required by
  early-expiration policy and version CAS (`ttl`, `createdTime`, access/visit
  counters, `expired`, and `version`). `startNanoTime` is process-local and is
  intentionally reset on deserialization; older payloads without these fields
  remain readable through the wall-clock fallback.
- **Early-expiration decision boundary**: TTL shortening compares the cached
  value's exact `payload.version`; the envelope `version` is format metadata,
  not the CAS token. Policy evaluation uses the configured TTL/threshold and
  has no unrelated absolute 60-second bypass.
- **Bloom CLEAN semantics**: Bloom tracks possible data-source membership,
  not current cache entries. CLEAN preserves existing bits and never uses a
  rebuilding marker or TTL window; false-positives are safe, but a missing bit
  may short-circuit Redis lookup and loader execution. Seed or maintain Bloom
  membership before enabling it for an existing data set.
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
