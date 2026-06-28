# ResiCache

**A protection-enhancement annotation ecosystem for Spring Cache** — beyond
`@Cacheable`, use a single `@RedisCacheable` annotation to add cache-penetration,
cache-breakdown, cache-avalanche, and hot-key early-refresh defenses to your
Redis cache. Protection is injected through a composable responsibility chain,
without re-inventing AOP.

[![CI](https://github.com/davidhlp/ResiCache/actions/workflows/ci.yml/badge.svg)](https://github.com/davidhlp/ResiCache/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> **Project status: early (v0.0.2) · Non-SLA best-effort · solo-maintained.**
> Read [⚠️ Known Limitations](#known-limitations) before any production use.

[简体中文](README.zh-CN.md)

## What it is

Spring Cache (`@Cacheable` / `@CachePut` / `@CacheEvict`) solves "caching", not
"protection" — cache penetration, breakdown, avalanche, and hot-key expiry are
left to the business layer. ResiCache turns these defenses into declarative
capabilities via **`@RedisCacheable` enhancement annotations** and a
**composable responsibility chain**.

- **Coexists with Spring Cache**: extends `RedisCacheManager` /
  `CacheInterceptor` — does not replace `@EnableCaching`, does not re-invent AOP.
- **Difference from JetCache**: JetCache focuses on **multi-level caching**;
  ResiCache focuses on **cache-defense-in-depth** — every handler on the chain is
  pluggable and composable, which JetCache does not offer.

## Features

| Feature | Description |
|---------|-------------|
| **Bloom filter** | Prevents cache penetration; blocks non-existent keys |
| **Distributed lock** | Redisson-based; prevents cache breakdown (**requires Redisson on classpath**) |
| **TTL jitter** | Randomizes TTL; prevents cache avalanche |
| **Null-value caching** | Caches `null`; prevents penetration |
| **Early expiration** | Async early refresh for hot keys; improves hit rate |
| **Composable chain** | Handlers strung together by priority; custom handlers can be inserted (differentiator) |
| **Safe serialization** | Whitelisted deserialization; defends against Jackson polymorphic-type attacks |

> ResiCache does **not** provide circuit breaking / rate limiting / multi-level
> local cache / Reactive support — see [Not in Scope](#not-in-scope).

## Architecture

ResiCache uses a **responsibility chain** for cache-write protection. Handler
ordering is defined in a single source of truth, the `HandlerOrder` enum, bound
via `@HandlerPriority`:

```
┌─────────────────────────────────────────────────────────────┐
│                    CacheHandlerChain                        │
├─────────────────────────────────────────────────────────────┤
│  ① BloomFilter      (100) ── Bloom filter, anti-penetration │
│  ② SyncLock         (200) ── Distributed lock, anti-breakdown│
│  ③ EarlyExpiration  (250) ── Early expiry, hot-key guard    │
│  ④ TTL              (300) ── TTL jitter, anti-avalanche     │
│  ⑤ NullValue        (400) ── Null caching, anti-penetration │
│  ⑥ ActualCache      (500) ── Actual Redis write             │
└─────────────────────────────────────────────────────────────┘
```

Any handler can set `output.skipRemaining=true` to short-circuit the rest of the
chain; `PostProcessHandler` callbacks run after the chain completes. Third-party
handlers can insert by extending the `HandlerOrder` enum.

## Quick start

### 1. Add the dependency

```xml
<dependency>
    <groupId>io.github.davidhlp</groupId>
    <artifactId>ResiCache</artifactId>
    <version>0.0.2</version>
</dependency>
```

### 2. Configure Redis

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

> ResiCache activates via Spring Boot auto-configuration (entry point
> `RedisCacheAutoConfiguration`, see `META-INF/spring/...AutoConfiguration.imports`).
> No extra `@EnableXxx` is required.

### 3. Enable caching

```java
@SpringBootApplication
@EnableCaching
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 4. Use the annotations

**Recommended: `@RedisCacheable` (the protection entry point)**

```java
@Service
public class UserService {
    @RedisCacheable(value = "users", key = "#id",
                    useBloomFilter = true,        // Bloom filter, anti-penetration
                    cacheNullValues = true,       // null caching
                    randomTtl = true,              // TTL jitter, anti-avalanche
                    variance = 0.2,                // jitter amplitude ±20%
                    enableEarlyExpiration = true)  // hot-key early refresh
    public User getUserById(Long id) {
        return userRepository.findById(id);
    }
}
```

**Compatible: `@Cacheable` (no protection)**

```java
@Cacheable(value = "users", key = "#id")  // coexists, but gains no protection
public User getUserById(Long id) { ... }
```

> `@Cacheable` coexists with ResiCache but **gains no protection** — the
> protection attributes (`useBloomFilter` / `randomTtl` / ...) live only on
> `@RedisCacheable`. Since v0.0.3, the default `nativeAnnotationMode=SELECTIVE`
> means plain `@Cacheable` is handled entirely by Spring's native cache
> infrastructure. Use `@RedisCacheable` for protection.

## Configuration

All properties use the `resi-cache.*` prefix (bound to `RedisProCacheProperties`).

### Master switches (new in v0.0.3)

```yaml
resi-cache:
  enabled: true                 # master kill-switch; false disables ResiCache entirely
  protection:
    enabled: true               # false skips bloom/lock/early-exp/null-value; TTL preserved (startup-only)
```

### Global

```yaml
resi-cache:
  default-ttl: 30m
  key-prefix: ""
  transaction-aware: false
  fail-on-spel-error: true
```

### Bloom filter

```yaml
resi-cache:
  bloom-filter:
    enabled: true
    expected-insertions: 100000
    false-probability: 0.01
    hash-cache-size: 10000
    rebuild-window-seconds: 30   # post-CLEAR rebuild window (s); 0 = disabled (v0.0.x behavior)
```

### Distributed lock

```yaml
resi-cache:
  sync-lock:
    timeout: 3000
    unit: MILLISECONDS
    prefix: "cache:lock:"
    local-only: false   # true = accept single-JVM sync when Redisson absent (else fail-fast)
```

### Early expiration (hot-key)

```yaml
resi-cache:
  early-expiration:
    enabled: true
    pool-size: 2
    max-pool-size: 10
    queue-capacity: 100
```

### Serialization safety

```yaml
resi-cache:
  serializer:
    type-property: "@class"
    polymorphic-typing-enabled: false   # off by default, safer
    fail-on-unknown-type: true
    allowed-package-prefixes:           # deserialization whitelist
      - "io.github.davidhlp."
      - "com.example."                   # ← you MUST add your own business packages
```

> ⚠️ The whitelist defaults to **only** `io.github.davidhlp.`. When caching custom
> business types (e.g. `com.example.User`), you **must** add your package to
> `allowed-package-prefixes`, otherwise deserialization throws.

### Annotation attributes (`@RedisCacheable`)

| Attribute | Default | Description |
|-----------|---------|-------------|
| `ttl` | 60 | Cache TTL (seconds) |
| `cacheNullValues` | false | Cache `null` |
| `useBloomFilter` | false | Enable Bloom filter |
| `expectedInsertions` | 10000 | Bloom expected insertions |
| `falseProbability` | 0.03 | Bloom false-positive rate |
| `randomTtl` | false | Enable TTL jitter |
| `variance` | 0.2 | TTL jitter amplitude |
| `enableEarlyExpiration` | false | Enable early expiry |
| `earlyExpirationThreshold` | 0.3 | Early-expiry threshold (remaining TTL ratio) |
| `sync` / `syncTimeout` | false / 10 | Sync wait & timeout |

> The five protection attributes default to **`false`** — enable each explicitly
> on `@RedisCacheable`. `sync=true` (anti-breakdown) requires Redisson on the
> classpath; **without it, ResiCache fails fast** (refuses to silently degrade to
> a single-JVM lock, which is useless across instances). For an explicit
> single-instance/test degradation, set `resi-cache.sync-lock.local-only=true`.
> A `resi-cache.protection.preset` to batch-enable them is planned for v0.2.0.

## How it works

**Cache penetration** — the Bloom filter intercepts requests for non-existent
keys before the cache layer. **Cache breakdown** — a distributed lock ensures
only one request loads the data. **Cache avalanche** — TTL randomization
(`TTL = baseTtl ± variance × baseTtl` when `randomTtl=true`) avoids mass
simultaneous expiry.

## Known Limitations

Hard limitations of v0.0.2 (all addressed in the [Roadmap](#roadmap)):

- **Protection off by default**: the five protection attributes default `false`;
  enable each explicitly → v0.2.0 adds `resi-cache.protection.preset=STRICT/STANDARD/NONE`.
- **Serialization envelope incompatible with Spring native**: ResiCache uses a
  `{version, payload}` envelope, incompatible with Spring's
  `GenericJackson2JsonRedisSerializer` / `JdkSerializer` — **existing projects
  must migrate**, otherwise the entire cache misses on cutover → v0.2.0 provides
  a `shadow → dual-write → cutover` migration tool.
- **Serialization whitelist defaults to the author's package**:
  `allowed-package-prefixes` defaults to `io.github.davidhlp.`; custom types must
  be added explicitly (see [Serialization safety](#serialization-safety)).
- **`nativeAnnotationMode` now defaults to `SELECTIVE`** (v0.0.3): plain
  `@Cacheable` is handled entirely by Spring's native cache infrastructure,
  removing the dual-advisor risk.
- **No Reactive support** (WebFlux / `Mono` / `Flux`): `RedisCacheInterceptor` is
  blocking; such methods log an explicit "caching will not take effect" warning.
- **`@CacheEvict(allEntries=true)` (CLEAN) is best-effort, not atomic** — parity
  with Spring's native `RedisCache.clear` / `DefaultRedisCacheWriter.clean`: it uses
  a SCAN cursor + batched UNLINK/DEL, so keys written mid-CLEAN may be stranded and
  the cache is briefly half-deleted on large key sets. Lua/MULTI atomicity is
  intentionally not used (Redis single-thread O(keyspace) block, Cluster
  cross-slot). When the Bloom filter is enabled, the `rebuild-window-seconds` window
  (v0.1.0) prevents silent nulls during the post-wipe rebuild.
- **No JMH benchmarks yet**: performance data lands in v0.3.0.

## Not in Scope

ResiCache deliberately omits these to avoid bloat — pair with dedicated tools:

- **Circuit breaking / rate limiting** → [Resilience4j](https://resilience4j.readthedocs.io/)
- **Multi-level local + remote cache** → [Caffeine](https://github.com/ben-manes/caffeine) for the local tier
- **Reactive caching** (WebFlux) → not supported; under roadmap evaluation

## Roadmap

| Version | Focus | Status |
|---|---|---|
| **v0.0.3** | Docs honesty + `resi-cache.enabled` kill-switch + Reactive explicit exclusion + dual-advisor fix | In progress |
| **v0.1.0** | Boot 4.0 / SDR 4.0 / Java 21 single-build (FIRE M0–M4 ✅ `38c514a`) + WS-1.2 P0 hardening (fail-fast sync / Cluster hash-tag / atomic CLEAN rebuild window ✅ `5a05d0a`) + WS-1.3 Path C ThreadLocal destruction (7-step sequence ✅ `a42a1c1`/`ceb3901`/`a483de9`/`b377c16`/`b9d6b40`/`cf4e2b1`) + first Maven Central publish | Pending (publish blocked on `OSSRH_*` → `MAVEN_USERNAME`/`MAVEN_PASSWORD` secret alignment) |
| **v0.2.0** | `protection.preset` + serialization compatibility + migration tool (single release unit) | Planned |
| **v0.3.0** | JMH benchmarks + observability (per-handler Micrometer tag) | Planned |
| **v1.0.0** | API freeze + official launch (sample project / comparison page / articles) | Planned |

See [CHANGELOG.md](CHANGELOG.md) for details.

## Dependencies

| Dependency | Version |
|------------|---------|
| Spring Boot | 3.4.13 (parent) |
| Java | 17+ |
| Redisson | 3.27.0 (optional) |
| Caffeine | 3.1.8 |
| Testcontainers | 1.20.4 (CI Docker compatibility override) |

Full matrix: [COMPATIBILITY.md](COMPATIBILITY.md).

## Project status & maintenance

- **Version**: v0.0.2 — Semantic Versioning < 1.0; APIs may change in minor
  releases; breaking items are marked ⚠️ in [CHANGELOG.md](CHANGELOG.md).
- **Maintenance**: solo-maintained ([DavidHLP](https://github.com/davidhlp)),
  **Non-SLA best-effort** — no guaranteed response time, but issues are actively
  addressed.
- **Contributing**: PRs welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).
- **Security**: report privately — see [SECURITY.md](SECURITY.md).
- **Compatibility**: see [COMPATIBILITY.md](COMPATIBILITY.md).

## License

[MIT License](LICENSE) © 2026 DavidHLP
