# Compatibility Matrix

ResiCache ships on a **single build line** (post-WS-1.1 FIRE; merged into `master`
at `38c514a`):

- **`master` branch — Spring Boot 4.0 / SDR 4.0 / Spring 7 / Java 21 / Redisson 3.50**.

Builds pass full `verify -B` (tests + JaCoCo 70%/40% gate + Testcontainers-backed integration tests).

> **Historical context**: Pre-FIRE (≤ commit `3e72546`), `master` carried a `boot3`
> line (Boot 3.4.13 / Java 17 / Redisson 3.27). WS-1.1 FIRE M1–M4 migrated to Boot 4
> and merged into `master` at `38c514a`; the dual-branch (`master` / `boot4`)
> strategy is **abandoned**. Boot 3.4 has been OSS-EOL since 2025-12; no `boot3`
> compatibility line is retained. See `CHANGELOG.md` WS-1.1 FIRE for migration context.

## Supported versions

### `master` line — Spring Boot 4.0 (sole line)

| Component | Version | Tested |
|-----------|---------|--------|
| Java | 21 | 21 |
| Spring Boot | 4.0.0 | 4.0.x (full `verify -B`) |
| Spring Framework | 7.x | (via Boot) |
| Spring Cache | 7.x | (via Boot) |
| Spring Data Redis | 4.0.x | (via Boot) |
| Redis Server | 7.x | 7.x |
| Redisson | 3.50.0 | 3.50.0 |
| Caffeine | 3.1.8 | 3.1.8 |

## Spring Boot version policy

- **`master` line (sole line)**: `spring-boot-starter-parent 4.0.0` + SDR 4.0 + Spring 7
  + Java 21 + Redisson 3.50. Build/verify locally with `./mvnw verify -B`
  (no profile flag needed). Boot 4 is configured directly in `pom.xml` as the
  sole build line — the historical `boot4`/`boot3` Maven profiles were removed
  in commit `9ad22bf`.
- **Boot 4 modularization note**: Boot 4 relocated packages
  (`o.s.b.autoconfigure.data.redis.*` → `o.s.b.data.redis.autoconfigure.*`,
  `o.s.b.actuate.health.*` → `o.s.b.health.contributor.*`) and SDR 4 renamed
  `RedisCacheWriter` methods (`remove`→`evict`, `clean`→`clear`). These breaks
  drove FIRE; all imports are Boot 4-aligned.
- **CI coverage**: `master` runs full `verify -B` on Java 21 against Boot 4.0
  via `.github/workflows/ci.yml`. The historical `.github/workflows/ci-boot4.yml`
  and the `compatibility` job in `ci.yml` have been removed (commit `6f00471`).
- **Not supported**: Spring Boot 2.x and 3.x. No `boot3` compatibility line is
  maintained; users on Boot 3.x should remain on ResiCache v0.0.x or migrate.
- **Pre-1.0 caveat**: matrix coverage is best-effort until 1.0.

## Optional dependencies

| Dependency | Required? | Notes |
|---|---|---|
| **Redisson** | Optional | Needed only for the distributed-lock (`sync=true`) protection against cache breakdown. Without it, `sync` degrades to a JVM-internal lock (**single-instance only** — does not coordinate across JVMs). Fully gated by `@ConditionalOnClass(RedissonClient.class)`. |
| **Micrometer / Actuator** | Optional | Without a `MeterRegistry`, cache metrics are silently skipped. `RedisCacheHealthIndicator` requires Actuator on the classpath. |
| **Caffeine** | Bundled | Used internally for the local hash cache and bloom-filter bitset. Not exposed as a user-facing multi-level cache (out of scope). |

## Serialization compatibility

⚠️ ResiCache serializes values in an internal `{version, payload}` envelope via
`SecureJackson` for safe deserialization. This is **not** wire-compatible with
Spring's `GenericJackson2JsonRedisSerializer` or `JdkSerializer`. Existing
caches must be **migrated** when adopting ResiCache, otherwise the entire cache
misses on cutover. See [README → Known Limitations](README.md#known-limitations).

## Known limitations

- **Reactive types**: `Mono<T>` / `Flux<T>` return types are **not supported**.
  ResiCache's interceptor is blocking; such methods log an explicit "caching
  will not take effect" warning and bypass ResiCache.
- **Async methods**: `@Async` cached methods are not supported for sync-lock and
  Bloom-filter enhancements.
- **Transaction-aware caching**: supported, but requires explicit
  `resi-cache.transaction-aware=true`.
- **Redis Cluster distributed locks**: lock keys are **hash-tag pinned** to the
  same slot as the cache key (WS-1.2b), so the lock and the data it guards
  co-locate on one node. Validated at the key-construction level.
