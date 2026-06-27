# Compatibility Matrix

ResiCache targets the **Spring Boot 3.x / Java 17+** ecosystem.

## Supported versions

| Component | Minimum | Recommended | Tested |
|-----------|---------|-------------|--------|
| Java | 17 | 21 | 17, 21 |
| Spring Boot | 3.3.x | 3.4.13 | 3.4.13 (full verify), 3.3.6 / 3.5.3 (compile-only) |
| Spring Cache | 6.1.x | 6.2.x | (via Boot) |
| Spring Data Redis | 3.3.x | 3.5.x | (via Boot) |
| Redis Server | 6.2 | 7.x | 6.2, 7.x |
| Redisson | 3.27.0 | 3.27.x | 3.27.0 |
| Caffeine | 3.1.8 | 3.1.x | 3.1.8 |

## Spring Boot version policy

- **Build parent**: `spring-boot-starter-parent 3.4.13` (declared in `pom.xml`).
- **CI coverage**: full `verify` (tests + JaCoCo gate) runs on **Java 17 / 21**
  against the build parent (Boot 3.4.13); an additional non-blocking compile
  check runs against Boot **3.3.6 / 3.5.3** on Java 17. ResiCache uses only
  stable Spring Cache / Spring Data Redis APIs, so it works across 3.3–3.5.
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
- **Redis Cluster distributed locks**: not yet validated — lock keys may span
  slots (no hash-tag pinning yet). Use single-node or sentinel for `sync=true`
  until verified.
