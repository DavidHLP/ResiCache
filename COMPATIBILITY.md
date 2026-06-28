# Compatibility Matrix

ResiCache ships on **two build lines** (dual-branch strategy):

- **`master` branch — Spring Boot 3.4.x / Java 17+** (default, maximum compatibility).
- **`boot4` branch — Spring Boot 4.0 / SDR 4.0 / Spring 7 / Java 21 / Redisson 3.50**
  (FIRE migration; see [CHANGELOG](CHANGELOG.md) WS-1.1 FIRE and `HANDOFF.md`).

Both lines pass full `verify` (tests + JaCoCo gate). Pick the branch matching your
Boot version.

## Supported versions

### `master` line — Spring Boot 3.4.x (default)

| Component | Minimum | Recommended | Tested |
|-----------|---------|-------------|--------|
| Java | 17 | 21 | 17, 21 |
| Spring Boot | 3.3.x | 3.4.13 | 3.4.13 (full verify), 3.3.6 / 3.5.3 (compile-only) |
| Spring Cache | 6.1.x | 6.2.x | (via Boot) |
| Spring Data Redis | 3.3.x | 3.5.x | (via Boot) |
| Redis Server | 6.2 | 7.x | 6.2, 7.x |
| Redisson | 3.27.0 | 3.27.x | 3.27.0 |
| Caffeine | 3.1.8 | 3.1.x | 3.1.8 |

### `boot4` line — Spring Boot 4.0 (FIRE)

| Component | Version | Tested |
|-----------|---------|--------|
| Java | 21 | 21 |
| Spring Boot | 4.0.0 | 4.0.x (full `verify -Pboot4`) |
| Spring Framework | 7.x | (via Boot) |
| Spring Data Redis | 4.0.x | (via Boot) |
| Redis Server | 7.x | 7.x |
| Redisson | 3.50.0 | 3.50.0 |
| Caffeine | 3.1.8 | 3.1.8 |

## Spring Boot version policy

- **`master` line (default)**: `spring-boot-starter-parent 3.4.13`.
- **`boot4` line (FIRE)**: `spring-boot-starter-parent 4.0.0` + SDR 4.0 + Spring 7
  + Java 21 + Redisson 3.50. Activate locally with `./mvnw verify -Pboot4`; the
  `boot4` branch pom is pre-configured (no `versions:set-parent` needed).
- **Dual-branch rationale**: Boot 4 modularized packages
  (`o.s.b.autoconfigure.data.redis.*` → `o.s.b.data.redis.autoconfigure.*`,
  `o.s.b.actuate.health.*` → `o.s.b.health.contributor.*`) and SDR 4 renamed
  `RedisCacheWriter` methods (`remove`→`evict`, `clean`→`clear`) — a single source
  tree cannot `import` both Boot 3 and Boot 4 packages, so the two lines live on
  separate branches (`master` / `boot4`) with their own CI workflows.
- **CI coverage**:
  - `master`: full `verify` (tests + JaCoCo gate) on Java 17 / 21 against Boot 3.4.13
    (`.github/workflows/ci.yml`); plus a non-blocking compile check against Boot 3.3.6 / 3.5.3.
  - `boot4`: full `verify -Pboot4` on Java 21 against Boot 4.0
    (`.github/workflows/ci-boot4.yml`, non-blocking during FIRE stabilization).
- **Not supported**: Spring Boot 2.x and 3.0.x–3.2.x (may work, untested).
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
misses on cutover. A `shadow → dual-write → cutover` migration tool is planned
for v0.2.0. See [README → Known Limitations](README.md#known-limitations).

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
  co-locate on one node. Validated at the key-construction level; full multi-node
  Cluster integration testing is planned for v0.2.0.
