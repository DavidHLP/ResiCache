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
