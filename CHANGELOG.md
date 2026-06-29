# Changelog

All notable changes to ResiCache are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html) **with the
pre-1.0 caveat below**.

## Pre-1.0 versioning policy

While the version is `0.x`, **APIs may change in any release — including patch
releases.** Breaking changes are marked with ⚠️ and explained in the release
notes. API stability is only guaranteed from `1.0.0` onward (see the
[Roadmap](README.md#roadmap) in README).

> **Note on the `v1.0` git tag.** A `v1.0` tag exists from an early development
> milestone ("defect remediation complete"). It **does not** denote an API-stable
> or publicly released version — the actual artifact version has always been
> `0.0.x`, and the public 1.0 release is tracked in the Roadmap. The tag is kept
> for history but carries no stability promise.

## [Unreleased] — v0.1.0 (in development)

### Changed
- ⚠️ **BREAKING (safety)** `SyncSupport` no longer **silently degrades** to a
  single-JVM `synchronized` monitor when `sync=true` is declared but no
  distributed lock backend is present (Redisson absent → no `LockManager`
  bean). It now **fails fast** with a clear `IllegalStateException` on the
  first cache miss — single-JVM coordination is useless across instances, and
  "distributed-but-actually-local" is the worst failure mode. A startup
  warning is also logged when the empty-backend condition is detected.
  **Migration:** for explicit single-instance/test degradation, set
  `resi-cache.sync-lock.local-only=true` (emits a
  `protection.degraded=local-only` warning; Observation event lands in v0.2.0).

### Added
- ⚙️ **WS-1.1 FIRE — Spring Boot 4.0 build line (dual-branch)**: ResiCache now
  builds and fully verifies (`verify -Pboot4`, 672 tests + JaCoCo 70%/40% gate)
  on **Boot 4.0 / SDR 4.0 / Spring 7 / Java 21 / Redisson 3.50** on the dedicated
  `boot4` branch, coexisting with the Boot 3.4.x `master` line. SDR 4 / Boot 4
  adaptations: `RedisCacheWriter.remove`→`evict` & `clean`→`clear` (delegating
  impls), `RedisCacheConfiguration.getTtl()`→`getTtlFunction().getTimeToLive(...)`,
  `RedisCacheManager` constructor arg reorder, Boot 4 package relocations
  (`DataRedisAutoConfiguration` / `DataRedisProperties` / `health.contributor`),
  `redisson-spring-boot-starter`→`redisson` core (the starter's auto-config
  hard-references Boot 3 classes), and a defensive `supportsAsyncRetrieve()=false`
  shim (Path C Step 6 will restore it). See [COMPATIBILITY.md](COMPATIBILITY.md)
  dual matrix and `.github/workflows/ci-boot4.yml`.
- `resi-cache.sync-lock.local-only` (default `false`) — explicit opt-in for
  single-JVM sync degradation when no distributed lock backend is available.
- `resi-cache.bloom-filter.rebuild-window-seconds` (default `30`; `0` = disabled) —
  after `@CacheEvict(allEntries=true)` (CLEAN) wipes the Bloom filter, opens a
  per-cacheName **rebuilding window** during which `mightContain` **fails open**
  (returns true), routing requests to the loader instead of silently returning
  null. See the WS-1.2c Fixed entry below.

### Fixed
- **Bloom filter CLEAR rebuilding window (WS-1.2c)**: previously, when
  `@CacheEvict(allEntries=true)` cleared the Bloom filter alongside the cache, the
  empty filter made every subsequent `mightContain` return false, and
  `RedisProCache.get(key, loader)`'s pre-check **silently returned null without
  invoking the loader** — violating the `@Cacheable` contract (a miss should call
  the loader and return the real value). This is a data-correctness defect, not a
  DB breakdown (the loader is never called); it only affects methods with
  `useBloomFilter=true`. `BloomSupport.clear` now opens a per-cacheName rebuilding
  window (Redis-backed flag, TTL = `rebuild-window-seconds`, Cluster-consistent via
  Redis + a 1s local cache) during which `mightContain` fails open, routing
  requests through the normal sync-lock + loader path; the window self-closes via
  Redis TTL. Set `rebuild-window-seconds=0` to restore v0.0.x behavior. Both the
  `RedisProCache.get(key, loader)` path and the chain path
  (`BloomFilterHandler.handleGet`) share `bloomSupport.mightContain`, so both are
  covered by this single-point fix.
- **Redis Cluster distributed lock hash-tag pinning (WS-1.2b)**: in Cluster mode,
  `DistributedLockManager` now ensures the lock key lands in the same slot as the
  cache key (via Redis hash-tag `{...}`), so the lock and the data it guards live
  on the same node and future in-lock MULTI/transactions won't hit cross-slot
  errors. `single`/`sentinel` modes are unchanged; lock-key format is also
  unchanged when the cache key already carries a hash-tag.

## [Unreleased] — planned for v0.0.3

### Added
- `resi-cache.enabled` master kill-switch — the auto-configuration is now gated
  by `@ConditionalOnProperty(prefix = "resi-cache", name = "enabled",
  matchIfMissing = true)`. Set `resi-cache.enabled=false` to fully disable
  ResiCache without removing the dependency.
- `resi-cache.protection.enabled` protection-chain switch — when `false`, the
  protection handlers (bloom/lock/early-expiration/null-value) are skipped but
  **TTL is preserved** (TtlHandler also computes the base TTL; disabling it would
  cause permanent caching). Startup-only (chain is cached as a singleton).
- Reactive return-type detection now logs an explicit **"caching will NOT take
  effect"** warning instead of implying a graceful fallback.
- `STABILITY.md`: API stability contract for the pre-1.0 0.x line. Lays out
  what is stable across all 0.x releases (annotations, `resi-cache.*`
  property keys, `{version,payload}` wire format), what may change
  (internals, defaults, package layout, pre-1.0 metric namespace), what
  never changes without a major bump, and the seven 1.0 graduation
  criteria. Required reading before any public API surface change per the
  loop chicken-egg gate.

### Changed
- ⚠️ **BREAKING** `nativeAnnotationMode` default changed from `FULL` →
  **`SELECTIVE`**: plain `@Cacheable` / `@CachePut` / `@CacheEvict` methods are
  now handled entirely by Spring's native cache infrastructure (no longer
  intercepted by ResiCache), which removes the dual-advisor risk. **Migration:**
  if you relied on ResiCache intercepting `@Cacheable`, set
  `resi-cache.native-annotation-mode=FULL` explicitly. Use `@RedisCacheable`
  for protection.

### Fixed
- Redisson is now a *true* optional dependency: the `RedissonClient` bean and
  all Redisson-specific configuration have been isolated into a dedicated
  configuration class with a class-level `@ConditionalOnClass(RedissonClient.class)`,
  so booting without Redisson on the classpath no longer risks a
  `NoClassDefFoundError`. (`DistributedLockManager` was already class-guarded;
  `SyncSupport` degrades to a JVM-internal monitor when no lock manager is
  present.)

### Documentation alignment
- Reconcile `CLAUDE.md` and `AGENTS.md` to current versions: Java 21 (was
  "17+"), Spring Boot 4.0.0 (was 3.4.13), Redisson 3.50.0 (was 3.27.0).
  `AGENTS.md` was reduced to a pointer to `CLAUDE.md` because its prior
  full-duplicate had drifted — it still listed the `a5ab55b`-removed
  `wrapper/`/`spi/`/`event/`/`evaluator/`/`CacheMetricsRecorder` and described
  customizability via "Java ServiceLoader" (superseded by Spring
  `@Bean` + `@ConditionalOnMissingBean`). Pointer preserves tool compatibility
  while preventing future drift. (loop round 1)

## [0.0.2] — current

### Removed — over-engineering cleanup (commit `a5ab55b`, ~2,989 lines)
⚠️ The following were removed as dead/over-engineered code. They had been
documented in README/CLAUDE.md but never shipped as stable features:

- `wrapper/` — `CircuitBreakerCacheWrapper`, `RateLimiterCacheWrapper`
  (circuit breaking / rate limiting) → use [Resilience4j](https://resilience4j.readthedocs.io/)
- `spi/` package (Java ServiceLoader provider model) — `BloomFilterProvider`,
  `LockProvider`, `RedissonLockProvider`, plus SPI-domain `BloomFilter`,
  `LockHandle`, `LockManager`. Note: only the ServiceLoader SPI package was
  removed — same-named interfaces live on under `protection/breakdown/LockManager`
  and `protection/bloom/filter/BloomIFilter`; customizability is via Spring beans.
- `event/` — `CacheEvictedEvent`
- `evaluator/` — `SpelConditionEvaluator`
- `observability/CacheMetricsRecorder` (only `RedisCacheHealthIndicator` remains)

Customizability is preserved through Spring beans: the strategy implementations
(`BloomIFilter`, `LockManager`) are plain `@Component`s, overridable via your own
`@Bean` + `@ConditionalOnMissingBean` — no Java ServiceLoader.

### Added
- Five protection mechanisms via a composable responsibility chain
  (ordering is a single source of truth in `HandlerOrder`, gap = 100):
  - Bloom filter (100) — penetration
  - Distributed lock (200) — breakdown
  - Early expiration (250) — hot key
  - TTL jitter (300) — avalanche
  - Null value (400) — penetration
- Enhancement annotations: `@RedisCacheable`, `@RedisCachePut`,
  `@RedisCacheEvict`, `@RedisCaching`.
- `SecureJackson` whitelisted deserialization (defense against Jackson
  polymorphic-type attacks) with a `{version, payload}` envelope.
- `RedisCacheHealthIndicator` (actuator health).

### Documentation alignment
README and CLAUDE.md still referenced the removed modules at the time; the LLM
wiki (`wiki/log.md`) records this discrepancy and treats source as the single
source of truth. The README rewrite in this cycle (see Unreleased) closes that
gap.

## [0.0.1] — initial (unpublished)
Initial development. No separate changelog was maintained; superseded by the
0.0.2 cleanup. The artifact version has been `0.0.x` throughout development.

---

Link reference policy: this file links to the README and wiki within the repo.
External links point to upstream tooling (Resilience4j, Caffeine, Keep a
Changelog, SemVer).
