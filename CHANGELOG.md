# Changelog

All notable changes to ResiCache are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) with the
pre-1.0 caveat below.

## Pre-1.0 caveat

While the version is `0.x`, **APIs may change in any release — including
patch releases.** Breaking changes are marked with ⚠️ in this changelog.
API stability is only guaranteed from `1.0.0` onward; see
[`STABILITY.md`](./STABILITY.md) for the contract that defines which
surfaces are stable in 0.x and which change.
> No local `v1.0` release tag is treated as an API-stability baseline here.
> Maven Central publishes `io.github.davidhlp:ResiCache:0.0.2`, but that
> artifact is from the earlier Boot 3 / Java 17 line. It is usable only for
> report-only compatibility analysis; a blocking compatibility gate waits for
> a same-line published artifact with matching tag provenance.

## [Unreleased] — current development

The project is on a **single build line**: Spring Boot
4.0 / SDR 4.0 / Spring 7 / Java 21 / Redisson 3.50.0. Dual-branch
(`master` / `boot4`) is abandoned; the current build line is `main` and Boot
4 is configured directly in `pom.xml`. See [`COMPATIBILITY.md`](./COMPATIBILITY.md).

Current milestones:

### Interface contract closure
- ⚠️ **Cache failure contract closure** — GET degrades to an observable miss;
  PUT, PUT_IF_ABSENT, and CLEAN propagate a typed runtime failure retaining the
  original cause; REMOVE remains observable best-effort. Callers that relied
  on swallowed write failures must catch the runtime failure or pin the
  previous 0.x version before migrating.
- **Explicit auto-configuration** — removed library-root component scanning,
  added typed default Bean back-off, and made `NullValuePolicy` the shared
  handler contract.
- **Async metadata closure** — capture now occurs before queueing work; worker
  activation restores prior ThreadLocal/MDC state in `finally`.
- **Integration naming closure** — `*IT` classes use `*IntegrationTest` and
  Surefire is the only Maven test lifecycle.
- ⚠️ **Sync without distributed backend fails fast** — `SyncSupport`
  no longer silently degrades to a single-JVM `synchronized` when
  `sync=true` is declared but no `LockManager` bean exists. Set
  `resi-cache.sync-lock.local-only=true` for explicit single-instance
  degradation.
- **Bloom filter CLEAR rebuilding window** — after
  `@CacheEvict(allEntries=true)`, `BloomSupport.clear` opens a Redis-backed
  rebuilding window (per-cacheName, TTL = `rebuild-window-seconds`,
  default `30`; `0` = disabled) during which `mightContain` fails open to
  the loader. Fixes the silent-null defect where empty-filter misses
  short-circuited the loader contract.
- **Redis Cluster distributed-lock hash-tag pinning** —
  `DistributedLockManager` ensures the lock key lands in the same Redis
  Cluster slot as the cache key (via `{...}` hash-tag), so the lock and
  the data it guards co-locate on one node.
- **ThreadLocal destruction sequence** — closes
  the destroy-on-pool-shutdown leak path. TDD: dedicated unit test plus
  Testcontainers IT verifying thread-id reuse after `RedisProCacheManager`
  close.

### Single-build FIRE M0–M4

- Historical FIRE M0–M4 verification established the Boot 4.0 / SDR 4.0 /
  Spring 7 / Java 21 / Redisson 3.50 line; current branch is `main`.
  Release claims still require current CI evidence.
- Adapter touch-points in source: `RedisCacheWriter.remove` → `evict`,
  `clean` → `clear` (delegating impls), `RedisCacheConfiguration.getTtl()`
  → `getTtlFunction().getTimeToLive(...)`, `RedisCacheManager`
  constructor arg reorder, Boot 4 package relocations
  (`DataRedisAutoConfiguration` / `DataRedisProperties` /
  `health.contributor`), `redisson-spring-boot-starter` → `redisson` core
  (the starter hard-references Boot 3 classes).
- CI: `.github/workflows/ci-boot4.yml` and the `compatibility` job in
  `  ci.yml` removed; Boot 4 is the sole line.

### Serializer hardening

- `SecureJacksonSerializerFactory` (@Component) — single entry point for
  `SecureJacksonRedisSerializer` wiring; eliminates the wired/unwired
  two-track bug from the earlier round. New unit test
  `SecureJacksonSerializerFactoryTest` with negative-wiring guard.
- **Wildcard `.*` suffix in `serializer.allowed-package-prefixes`** —
  `WhitelistPolicy.matchesPrefix` allows `com.example.*` to match any
  depth below `com.example.` (dot-boundary protected); literal prefixes
  keep their unchanged behavior.
- `SerializerWhitelistStartupGuard` (@Component + IT) — emits WARN on
  `ApplicationReadyEvent` when whitelist is `null` / `[]`; closes the
  "silent runtime failure on whitelist misconfig" footgun.
- `SerializationPreFlightProbe` — opt-in startup scan (`probe-enabled` +
  `probe-sample-size`, default 100) detects legacy non-envelope values;
  diagnostic-only, non-fatal.

### Master switches and operational policy

- `resi-cache.enabled` master kill-switch (auto-config gated by
  `@ConditionalOnProperty(prefix="resi-cache", name="enabled",
  matchIfMissing=true)`).
- `resi-cache.protection.enabled` — disables protection-chain handlers
  but **preserves TTL** (TtlHandler computes the base TTL).
- `nativeAnnotationMode` default `FULL` → **`SELECTIVE`** ⚠️ — plain
  `@Cacheable` is now handled by Spring's native cache infrastructure
  (resolves the dual-advisor risk). Set
  `resi-cache.native-annotation-mode=FULL` to opt back into pre-0.0.3
  behavior.
- Per-handler observability — `resicache.handler.fired` counter (tag
  `handler` = subclass simple name) + `[chain]` DEBUG with MDC
  `requestId` correlating one GET/PUT across all evaluated handlers;
  built on `ThreadLocalRandom` (no `SecureRandom` contention on hot path).
- Reactive return-type detection now logs an explicit **"caching will NOT
  take effect"** warning instead of implying a graceful fallback.

### Diagnosability and contributor infrastructure

- `SecureJackson` whitelist rejection message now includes the remediation
  property key `resi-cache.serializer.allowed-package-prefixes`.
- `CONTRIBUTING.md` — bus-factor disclosure (Maintainers section; bus
  factor 1 documented); JDK requirement aligned to 21 (matches `pom.xml`).
- Composite GitHub Action `.github/actions/setup-jdk-21` — single source
  for CI/PR JDK distribution and Maven cache. Release intentionally uses its
  inline setup-java step because deploy credentials require extra inputs.
- GitHub contributor templates
  (`.github/ISSUE_TEMPLATE/{bug_report,feature_request,config}.yml` +
  `.github/PULL_REQUEST_TEMPLATE.md`); `CODEOWNERS` relocated to
  `.github/`.
- Versions reconciled across `README.md` / `README.zh-CN.md` /
  `CLAUDE.md` to **Boot 4.0.0 / Java 21 /
  Redisson 3.50.0 / Caffeine 3.1.8** (was 3.4.13 / 17+ / 3.27.0).
- **JetCache coverage arithmetic correction** — JetCache
  Issue #269 (TTL jitter) is **closed unimplemented**, so
  ResiCache–JetCache coverage is **3/5** (not 4/5); ResiCache's true
  technical increment is **Bloom + TTL jitter + pluggable responsibility
  chain**.

### Fixed

- **`resi-cache.serializer.*` properties were silently dropped by the
  `RedisTemplate<String, Object> redisCacheTemplate` bean** (writer path).
  Setting `allowed-package-prefixes` / `polymorphic-typing-enabled` was
  honored on the `@Cacheable` path but not by the writer path → two code
  paths, two different whitelists, silent inconsistency. Fixed by
  threading `RedisProCacheProperties.SerializerProperties` through the
  writer bean. Regression test: `RedisConnectionConfigurationTest`
  (Testcontainers, `@DynamicPropertySource`).
- **Redisson is now a true optional dependency** — `RedissonClient` bean
  and Redisson-specific configuration isolated under a class-level
  `@ConditionalOnClass(RedissonClient.class)`; booting without Redisson
  no longer risks a `NoClassDefFoundError`.
- **`.github/workflows/release.yml` `JAVA_VERSION` lag** —
  `env.JAVA_VERSION: '17'` was not synchronized in the FIRE cut;
  bumped to `'21'` to match `pom.xml <java.version>21</java.version>`.
- Whitelist rejection message now mentions the remediation property key.

## [0.0.2] — current

### Removed — over-engineering cleanup (~2,989 lines)

⚠️ The following were removed as dead / over-engineered code (had been
documented but never shipped as stable features):

- `wrapper/` — `CircuitBreakerCacheWrapper`, `RateLimiterCacheWrapper`
  → use [Resilience4j](https://resilience4j.readthedocs.io/)
- `spi/` Java ServiceLoader package (`BloomFilterProvider`,
  `LockProvider`, `RedissonLockProvider`, etc.) — same-named interfaces
  retained under `protection/breakdown/LockManager` and
  `protection/bloom/filter/BloomIFilter`; customizability is via Spring
  `@Bean` + `@ConditionalOnMissingBean`.
- `event/` — `CacheEvictedEvent`
- `evaluator/` — `SpelConditionEvaluator`
- `observability/CacheMetricsRecorder` (only
  `RedisCacheHealthIndicator` retained)

### Added

- Five protection mechanisms via a composable responsibility chain
  (single source of truth in `HandlerOrder`, gap = 100):
  - Bloom filter (100) — penetration
  - Distributed lock (200) — breakdown
  - Early expiration (250) — hot key
  - TTL jitter (300) — avalanche
  - Null value (400) — penetration
- Enhancement annotations: `@RedisCacheable`, `@RedisCachePut`,
  `@RedisCacheEvict`, `@RedisCaching`.
- `SecureJackson` whitelisted deserialization with `{version, payload}`
  envelope.
- `RedisCacheHealthIndicator` (actuator health).

## [0.0.1] — initial (unpublished)

Initial development; no separate changelog was maintained (superseded by
the 0.0.2 cleanup). Artifact version has been `0.0.x` throughout
development.

---

Link reference policy: this file links to the README within the
repo. External links point to upstream tooling (Resilience4j, Caffeine,
Keep a Changelog, SemVer).
