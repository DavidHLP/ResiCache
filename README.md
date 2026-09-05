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

Each handler returns a typed `HandlerResult` carrying explicit `FlowControl`
(`CONTINUE`, `SKIP_ALL`, `TERMINATE`) to govern execution without hidden state.
Handlers opting into post-processing (e.g. Bloom filter async backfilling)
override `requiresPostProcess` and `afterChainExecution`.
Built-in handlers are registered by the library's auto-configuration, which
scans only the library-internal `cache` runtime package. A host application's
custom handler must be a host-scanned `@Component` or an application `@Bean`,
implement `CacheHandler`, and use `@HandlerPriority`; the library's internal
scan does not see host packages.

## Quick start

### 1. Add the dependency

```xml
<dependency>
    <groupId>io.github.davidhlp</groupId>
    <artifactId>ResiCache</artifactId>
    <version>0.0.2</version>
</dependency>
```

> The `0.0.2` artifact on Maven Central is the **earlier Boot 3 / Java 17
> line** (verified 2026-09-05). The current Boot 4 / Java 21 line has no
> published artifact yet; the coordinate above is the planned one.

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
> `@RedisCacheable`. In the current unreleased contract, the default
> `nativeAnnotationMode=SELECTIVE` means plain `@Cacheable` is handled
> entirely by Spring's native cache infrastructure. Use `@RedisCacheable` for
> protection.

## Configuration

Most properties use the `resi-cache.*` prefix and bind to
`RedisProCacheProperties`; the four `resi-cache.bloom.*` implementation keys
are explicitly bound by auto-configuration and described in additional
configuration metadata.

### Master switches (current unreleased contract)

```yaml
resi-cache:
  enabled: true                 # master kill-switch; false disables ResiCache entirely
  protection:
    enabled: true               # false skips bloom/lock/early-exp/null-value; TTL preserved
    bloom-filter-enabled: null  # per-mechanism overrides, resolved once at startup:
    sync-lock-enabled: null     #   null inherits the total switch; false disables that
    early-expiration-enabled: null # mechanism only; true cannot re-enable a mechanism
    null-value-enabled: null    #   when the total switch is false. Restart to apply.
```

Protection toggles are **startup-only**: the handler chain is built once and
cached; per-mechanism `true` cannot override a `false` total switch, and
changing protection configuration requires an application restart. TTL and
the actual-cache handler always remain.

### Global

```yaml
resi-cache:
  default-ttl: 30m
  key-prefix: ""
  transaction-aware: false
```

### Bloom filter

```yaml
resi-cache:
  bloom:
    prefix: "bf:"
    bit-size: 8388608
    hash-functions: 3
    hash-cache-size: 10000
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
  protection:
    early-expiration-enabled: true  # optional mechanism override
  early-expiration:
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
>
> **Wildcard form (current unreleased):** any prefix ending in `.*` is a wildcard
> sentinel — it matches the class directly (`com.example.Foo`), all sub-package
> classes (`com.example.sub.Bar`, `com.example.foo.bar.baz.Qux`, …), and is
> dot-boundary protected (so `com.example.*` does **not** match `com.exampleX.Foo`).
> Use it when you want to allow a whole package subtree without listing each
> sub-package.
>
> ```yaml
> resi-cache:
>   serializer:
>     allowed-package-prefixes:
>       - "io.github.davidhlp."
>       - "com.example.*"        # entire com.example subtree in one entry
> ```

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

## How it works

**Cache penetration** — the Bloom filter intercepts requests for non-existent
keys before the cache layer. **Cache breakdown** — a distributed lock ensures
only one request loads the data. **Cache avalanche** — TTL randomization
(`TTL = baseTtl ± variance × baseTtl` when `randomTtl=true`) avoids mass
simultaneous expiry.

## Comparison

ResiCache is one of four common options for caching on top of Redis: JetCache,
Caffeine, raw Redisson, and ResiCache. The project ships under one line:
**"ResiCache for Redisson — the declarative cache protection chain Redisson
forgot to ship"**.

| Capability | JetCache | Caffeine | Raw Redisson | **ResiCache** |
|------------|:--------:|:--------:|:------------:|:-------------:|
| Multi-level local + remote cache | ✅ | local only | — | — |
| Bloom filter (anti-penetration) | — | — | manual | ✅ |
| TTL jitter (anti-avalanche) | — | — | manual | ✅ |
| Distributed breakdown lock | — | — | manual | ✅ |
| Null-value caching | — | — | manual | ✅ |
| Hot-key early refresh | — | — | manual | ✅ |
| Declarative `@Annotation` chain | partial | — | — | ✅ |
| Broadcast invalidation | ✅ | — | — | — |

**Headline takeaway: the 3 protections JetCache is missing, in one
Redisson-native chain** — bloom-filter (penetration), TTL jitter (avalanche),
and distributed breakdown lock (breakdown). ResiCache is the Redisson
companion that closes those gaps; JetCache is the multi-level / broadcast
invalidator. The two are complementary in scope, not direct substitutes.

## Known Limitations

- **Protection off by default**: the five protection attributes default `false`;
  enable each explicitly on `@RedisCacheable`.
- **Serialization envelope incompatible with Spring native**: ResiCache uses a
  `{version, payload}` envelope, incompatible with Spring's
  `GenericJackson2JsonRedisSerializer` / `JdkSerializer` — **existing projects
  must migrate**, otherwise the entire cache misses on cutover.
- **Serialization whitelist defaults to the author's package**:
  `allowed-package-prefixes` defaults to `io.github.davidhlp.`; custom types must
  be added explicitly (see [Serialization safety](#serialization-safety)).
- **`nativeAnnotationMode` defaults to `SELECTIVE`**: plain `@Cacheable` is
  handled entirely by Spring's native cache infrastructure, removing the
  dual-advisor risk. Use `@RedisCacheable` for protection.
- **Cache I/O failure semantics**: GET degrades to a miss and logs the
  failure; PUT, PUT_IF_ABSENT, and CLEAN throw a typed runtime failure
  retaining the original cause; REMOVE is observable best-effort and does not
  throw. **Read-through (`get(key, loader)`) is availability-first**: the
  loader's successful value is always returned — a cache write-back failure
  after a successful load is logged (redacted) and never overrides the value;
  loader failures still surface as Spring `Cache.ValueRetrievalException`.
- **`@CacheEvict(allEntries=true)` (CLEAN) is best-effort, not atomic** — parity
  with Spring's native `RedisCache.clear` / `DefaultRedisCacheWriter.clean` —
  it uses a SCAN cursor + batched UNLINK/DEL, so keys written mid-CLEAN may be
  stranded and the cache is briefly half-deleted on large key sets. Lua/MULTI
  atomicity is intentionally not used (Redis single-thread O(keyspace) block,
  Cluster cross-slot). Bloom is a data-source existence hint: CLEAN removes
  cache entries but preserves existing Bloom bits, so it can only introduce
  false-positives and never block a valid loader with a false-negative.

## Not in Scope

ResiCache deliberately omits these to avoid bloat — pair with dedicated tools:

- **Circuit breaking / rate limiting** → [Resilience4j](https://resilience4j.readthedocs.io/)
- **Multi-level local + remote cache** → [Caffeine](https://github.com/ben-manes/caffeine) for the local tier
- **Reactive caching** (WebFlux) → not supported

## Dependencies

| Dependency | Version |
|------------|---------|
| Spring Boot | 4.0.0 (parent) |
| Java | 21 |
| Redisson | 3.50.0 (optional) |
| Caffeine | 3.1.8 |
| Testcontainers | 1.20.6 |

Full matrix: [COMPATIBILITY.md](COMPATIBILITY.md).

## Project status & maintenance

- **Version**: v0.0.2 — Semantic Versioning < 1.0; APIs may change in minor
  releases; breaking items are marked ⚠️ in [CHANGELOG.md](CHANGELOG.md).
- **Maintenance**: solo-maintained ([DavidHLP](https://github.com/davidhlp)),
  **Non-SLA best-effort** — no guaranteed response time, but issues are actively
  addressed.
- **Contributing**: PRs welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).
- **Performance Benchmarks**: JMH baseline results & SLOs — see [PERFORMANCE.md](PERFORMANCE.md).
- **API Stability Contract**: 0.x vs 1.0 stability guarantees — see [STABILITY.md](STABILITY.md).
- **Architecture Decisions**: accepted decisions and rationale — see
  [ADR index](docs/adr/README.md).
- **Compatibility Matrix**: supported Spring Boot / Java / Redisson lines — see [COMPATIBILITY.md](COMPATIBILITY.md).
- **Security**: report privately — see [SECURITY.md](SECURITY.md).

## License

[MIT License](LICENSE) © 2026 DavidHLP
