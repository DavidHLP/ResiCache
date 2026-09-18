# ResiCache

**Protection-in-depth for Spring Cache on Redis.** ResiCache adds declarative
cache-penetration, cache-breakdown, cache-avalanche, and hot-key refresh
protection through a composable responsibility chain, while keeping Spring
Cache as the application-facing model.

[![CI](https://github.com/davidhlp/ResiCache/actions/workflows/ci.yml/badge.svg)](https://github.com/davidhlp/ResiCache/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[English](README.md) · [简体中文](README.zh-CN.md)

> [!WARNING]
> ResiCache is pre-1.0 (`v0.0.2`), non-SLA, and currently maintained by one
> maintainer. The current `main` line targets Spring Boot 4.0 and Java 21, but
> it does not have a matching Maven Central artifact yet. Read
> [Compatibility](COMPATIBILITY.md) and [Limitations](#limitations) before
> adopting it in production.

## Contents

- [Overview](#overview)
- [Features](#features)
- [Compatibility and requirements](#compatibility-and-requirements)
- [Quick start](#quick-start)
- [How it works](#how-it-works)
- [Configuration](#configuration)
- [Extension points](#extension-points)
- [Runtime semantics](#runtime-semantics)
- [Comparison](#comparison)
- [Limitations](#limitations)
- [Not in scope](#not-in-scope)
- [Project layout](#project-layout)
- [Development](#development)
- [Project status and support](#project-status-and-support)
- [Security](#security)
- [License](#license)

## Overview

Spring Cache provides a consistent programming model for caching, but it does
not by itself provide protection against cache penetration, breakdown, avalanche,
or hot-key expiry. ResiCache adds those protections as explicit capabilities on
top of Spring Cache and Redis.

ResiCache is designed to:

- **Keep Spring Cache at the boundary**: applications still use
  `@EnableCaching`, cache operations, and Spring's cache abstractions.
- **Make protection composable**: each protection mechanism is a typed handler
  in one ordered chain rather than unrelated advice scattered across services.
- **Fail closed at important boundaries**: missing distributed-lock support,
  unsafe serialization, and invalid configuration are observable instead of
  silently becoming weaker guarantees.
- **Stay focused**: it is not a replacement for a multi-level cache,
  circuit-breaker, rate-limiter, or reactive cache framework.

## Features

| Capability | What it provides |
|---|---|
| Bloom filter | Rejects requests for keys that are not known to exist, reducing cache penetration |
| Distributed lock | Redisson-based coordination for cache breakdown protection |
| TTL jitter | Randomizes expiration to reduce synchronized cache avalanche |
| Null-value caching | Caches negative lookups when explicitly enabled |
| Early expiration | Refreshes hot keys before their normal expiration boundary |
| Composable handler chain | Orders protection mechanisms through `HandlerOrder` and `@HandlerPriority` |
| Safe serialization | Uses a controlled deserialization whitelist and an internal wire envelope |
| Spring Cache integration | Works with Spring Cache instead of introducing a second AOP model |

All five protection attributes on `@RedisCacheable` default to `false`; enable
the mechanisms that match the application's risk profile.

## Compatibility and requirements

The repository currently ships one supported build line:

| Component | Current `main` line |
|---|---:|
| Java | 21 |
| Spring Boot | 4.0.0 |
| Spring Framework / Spring Cache | 7.x, through Boot 4 |
| Spring Data Redis | 4.0.x |
| Redis Server | 7.x |
| Redisson | 3.50.0, optional |
| Caffeine | 3.1.8, bundled for internal support |
| Docker | Required for Testcontainers-backed verification |
| Testcontainers | 1.20.6, test scope |

See the complete matrix and known compatibility boundaries in
[COMPATIBILITY.md](COMPATIBILITY.md).

### Published artifact status

The current Boot 4 / Java 21 line is source-first and has not been published to
Maven Central. The `io.github.davidhlp:ResiCache:0.0.2` coordinate is a
historical Boot 3 / Java 17 artifact; do not use it as a dependency for the
current `main` line.

To try the current line, [build it from source](#development) or use a release
that explicitly declares its supported compatibility line.

## Quick start

### 1. Build and install the current source line locally

```bash
git clone https://github.com/davidhlp/ResiCache.git
cd ResiCache
./mvnw -Punit test -B
./mvnw install -DskipTests -B
```

The first command is the no-Docker contributor check. The second installs the
current source checkout into the local Maven repository for a local consumer;
it does not publish an artifact. Do not confuse that local build with the
historical `0.0.2` artifact on Maven Central.

For a local consumer application, add the current checkout's coordinates to
that application's `pom.xml`:

```xml
<dependency>
    <groupId>io.github.davidhlp</groupId>
    <artifactId>ResiCache</artifactId>
    <version>0.0.2</version> <!-- keep in sync with this checkout's root pom.xml -->
</dependency>
```

The dependency resolves from the local Maven repository. Continue with the
configuration and application examples below in that consumer application,
not in the ResiCache checkout. The full Redis and Redis Cluster verification
command is documented in [Development](#development).

### 2. Configure Redis

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      # timeout: 2s
resi-cache:
  redis:
    mode: single
    host: localhost
    port: 6379
    database: 0
    tls-enabled: false
```

`spring.data.redis.*` and `resi-cache.redis.*` configure separate clients.
`spring.data.redis.*` configures the Spring Data Redis connection factory used
for ResiCache cache I/O. `resi-cache.redis.*` configures the Redisson deployment
used by distributed locking and synchronization; `resi-cache.redisson.*`
controls its pool, timeout, and retry settings. In single mode, Redisson may
fall back to Spring Data Redis host, port, database, and password values when
the corresponding `resi-cache.redis.*` values are unset. Keep the effective
endpoints and credentials aligned when using `sync=true`.

ResiCache is discovered through Spring Boot auto-configuration via
`RedisCacheAutoConfiguration`. It does not add `@EnableCaching` for the
application; the application remains responsible for enabling Spring Cache.

### 3. Enable Spring Cache

```java
@SpringBootApplication
@EnableCaching
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 4. Use the protected annotation

```java
@Service
public class UserService {

    @RedisCacheable(
            value = "users",
            key = "#id",
            cacheNullValues = true,
            randomTtl = true,
            variance = 0.2,
            enableEarlyExpiration = true)
    public User getUserById(Long id) {
        return userRepository.findById(id);
    }
}
```

The protection attributes are deliberately explicit. A plain `@Cacheable`
remains compatible with Spring Cache, but it does not gain ResiCache protection
in the default `nativeAnnotationMode=SELECTIVE` mode:

```java
@Cacheable(value = "users", key = "#id")
public User getUserById(Long id) {
    // Spring's native cache path; use @RedisCacheable for protection.
}
```

## How it works

### Handler chain

Protection is assembled as a responsibility chain. The order is defined by the
`HandlerOrder` enum and attached to handlers through `@HandlerPriority`:

```text
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

Each handler returns a typed `HandlerResult` with explicit `FlowControl`
decisions (`CONTINUE`, `SKIP_ALL`, or `TERMINATE`). A handler can opt into
post-processing through `requiresPostProcess` and
`afterChainExecution`; the chain engine owns progression and keeps the main
path separate from isolated post-processing failures.

Built-in handlers are registered by the library's auto-configuration, which
scans the library-internal runtime package. A host application's custom handler
must be discovered by the host application as an `@Component` or `@Bean`; the
library's internal scan does not scan host packages.

### Protection boundaries

- **Cache penetration**: the Bloom filter treats a missing membership bit as a
  definite miss. The default implementation is populated after successful cache
  writes; it does not scan or rebuild from the data source. Before enabling it
  for a cache whose keys are not already in the filter, seed or maintain the
  public `BloomIFilter` seam.
- **Cache breakdown**: the sync handler coordinates concurrent loads through a
  distributed lock when Redisson or another `LockManager` is available.
- **Cache avalanche**: TTL jitter spreads expiration times when `randomTtl=true`.
- **Hot-key expiry**: early expiration schedules refresh work before the normal
  expiry boundary when explicitly enabled.
- **Negative lookups**: null-value caching can retain an absent result when the
  annotation or configuration enables it.

## Configuration

Most settings use the `resi-cache.*` prefix and bind to
`RedisProCacheProperties`. Configuration is resolved during startup; changing
protection switches requires an application restart.

### Enablement and protection switches

```yaml
resi-cache:
  enabled: true
  native-annotation-mode: SELECTIVE  # FULL | NONE | SELECTIVE
  protection:
    enabled: true
    bloom-filter-enabled: null        # null inherits protection.enabled
    sync-lock-enabled: null
    early-expiration-enabled: null
    null-value-enabled: null
```

`resi-cache.enabled=false` disables the library auto-configuration. The
`protection.enabled` switch disables Bloom, sync-lock, early-expiration, and
null-value protection while keeping the base TTL and actual-cache handlers.
Per-mechanism `true` cannot re-enable a mechanism when the total protection
switch is `false`.

`native-annotation-mode` controls Spring's native cache annotations:

- `SELECTIVE` (default): leaves methods with no ResiCache annotation on
  Spring's native path and skips a native operation when the corresponding
  ResiCache operation is present. Mixed or non-corresponding annotation
  combinations can still produce multiple operations or advisor interception;
  do not mix annotations on one method without testing the result.
- `FULL`: converts all supported Spring cache annotations, including when
  ResiCache annotations are present; mixed or non-corresponding annotations
  can produce multiple operations or advisor interception, so test those
  combinations.
- `NONE`: ignores native Spring cache annotations in the ResiCache operation
  source.

### Global settings

```yaml
resi-cache:
  default-ttl: 30m
  key-prefix: ""
  transaction-aware: false
```

### Metrics and health

Metrics are opt-in and require both the property and an available
`MeterRegistry`:

```yaml
resi-cache:
  metrics:
    enabled: true
```

The Redis cache health indicator uses the same explicit property and requires
Spring Boot Actuator.

### Bloom filter

```yaml
resi-cache:
  bloom:
    prefix: "bf:"
    bit-size: 8388608
    hash-functions: 3
    hash-cache-size: 10000
```
These settings are bound under the `resi-cache.bloom.*` prefix.

### Distributed lock

```yaml
resi-cache:
  sync-lock:
    timeout: 3000
    unit: MILLISECONDS
    prefix: "cache:lock:"
    local-only: false
```

`sync=true` requires a distributed lock implementation such as Redisson. With
`local-only=false` (the default), missing distributed support fails closed
instead of silently claiming multi-instance protection. Set `local-only=true`
only when single-JVM degradation is an explicit, acceptable choice.

### Early expiration

```yaml
resi-cache:
  protection:
    early-expiration-enabled: true
  early-expiration:
    pool-size: 2
    max-pool-size: 10
    queue-capacity: 100
```

### Redis deployment

```yaml
resi-cache:
  redis:
    mode: single                  # single | cluster | sentinel
    host: localhost
    port: 6379
    database: 0
    tls-enabled: false
    # cluster-nodes: [host1:6379, host2:6379]
    # sentinel-master: mymaster
    # sentinel-nodes: [host1:26379]
```

The deployment validator checks mode-specific fields during configuration
binding. `resi-cache.redis.*` is the Redisson deployment path; `spring.data.redis.*`
configures the separate connection factory used for cache I/O. In single mode,
Redisson may fall back to Spring Data Redis host, port, database, and password
values when the corresponding `resi-cache.redis.*` values are unset. The
`resi-cache.redisson.*` namespace controls Redisson pool, timeout, and retry
settings. Keep the effective endpoint configurations aligned when `sync=true`.
Credentials, when needed, belong in the application's secret management system
rather than in a committed README snippet.

### Serialization safety

```yaml
resi-cache:
  serializer:
    type-property: "@class"
    polymorphic-typing-enabled: false
    fail-on-unknown-type: true
    allowed-package-prefixes:
      - "io.github.davidhlp"
      - "com.example.*"
```

The default whitelist is the literal prefix `io.github.davidhlp`, so
applications should add their own packages explicitly. For dot-boundary subtree
matching, use a prefix ending in `.*`; for example, `com.example.*` matches
`com.example.User` and `com.example.orders.Order`, but not `com.exampleX.User`.

### Per-cache overrides

```yaml
resi-cache:
  caches:
    users:
      ttl: 10m
      cache-null-values: true
      key-prefix: "users:"
```

### `@RedisCacheable` attributes

| Attribute | Default | Purpose |
|---|---:|---|
| `ttl` | `60` | Cache TTL in seconds |
| `cacheNullValues` | `false` | Cache `null` results |
| `useBloomFilter` | `false` | Enable Bloom-filter protection |
| `expectedInsertions` | `100000` | Expected Bloom-filter insertions |
| `falseProbability` | `0.01` | Bloom false-positive target |
| `randomTtl` | `false` | Enable TTL jitter |
| `variance` | `0.2` | TTL jitter amplitude |
| `enableEarlyExpiration` | `false` | Enable hot-key early refresh |
| `earlyExpirationThreshold` | `0.3` | Remaining-TTL ratio that triggers refresh |
| `sync` / `syncTimeout` | `false` / `10` | Enable synchronized loading and its wait timeout |

## Extension points

### Annotation family

The public annotation family mirrors Spring Cache operations:

- `@RedisCacheable` for read-through caching and protection attributes.
- `@RedisCachePut` for explicit cache writes.
- `@RedisCacheEvict` for cache removal.
- `@RedisCaching` for grouping multiple ResiCache operations on one method or
  type.

`@RedisCaching` can expose operations at type level, but the protection-policy
fields inside its composed annotations are evaluated at method level. A
type-level declaration does not apply those fields to otherwise unannotated
methods; repeat the relevant `@RedisCacheable`, `@RedisCachePut`, or
`@RedisCacheEvict` at method level when the method needs that policy.

### Custom handlers

A custom handler implements the public `CacheHandler` contract and uses
`@HandlerPriority` with a `HandlerOrder` value. It must be registered in the
host application's component scan or supplied as an application bean. The
handler can return a typed `HandlerResult` and can opt into the post-processing
hooks without managing a linked-list successor itself.

Only the documented public seams are intended for replacement. See
[STABILITY.md](STABILITY.md) before depending on a public type or changing
handler behavior.

## Runtime semantics

### Cache I/O failure behavior

| Operation | Behavior |
|---|---|
| GET | Degrades to a cache miss and records the internal failure |
| PUT / PUT_IF_ABSENT / CLEAN | Throws a typed runtime failure retaining the original cause |
| REMOVE | Observable best-effort removal; does not throw for the removal failure |
| `get(key, loader)` with a successful loader | Returns the loader value even if write-back fails; write-back failure is logged without the raw key |
| `get(key, loader)` with a failed loader | Surfaces Spring's `Cache.ValueRetrievalException` |

`@CacheEvict(allEntries=true)` / CLEAN is best-effort and non-atomic, using a
SCAN cursor and batched deletion. Bloom membership bits are a separately
maintained set, populated by successful writes by default; they are not
automatically rebuilt from the data source. Clearing cache entries does not
remove those bits.

### Serialization migration

ResiCache stores values in an internal `{version, payload}` envelope. It is not
wire-compatible with Spring's `GenericJackson2JsonRedisSerializer` or
`JdkSerializer`. Existing applications should use a bounded
shadow-read → dual-write → cutover migration instead of assuming an in-place
serializer swap is safe. See the migration guidance in
[COMPATIBILITY.md](COMPATIBILITY.md).

## Comparison

ResiCache is intentionally narrower than general-purpose caching frameworks:

| Capability | JetCache | Caffeine | Raw Redisson | **ResiCache** |
|---|:---:|:---:|:---:|:---:|
| Multi-level local + remote cache | Yes | Local only | — | — |
| Bloom filter | — | — | Manual | Yes |
| TTL jitter | — | — | Manual | Yes |
| Distributed breakdown lock | — | — | Manual | Yes |
| Null-value caching | — | — | Manual | Yes |
| Hot-key early refresh | — | — | Manual | Yes |
| Declarative protection chain | Partial | — | — | Yes |
| Broadcast invalidation | Yes | — | — | — |

JetCache is a multi-level and broadcast-invalidation option. ResiCache is a
Redisson-oriented protection chain. They solve different parts of the caching
problem and are not presented as drop-in substitutes.

## Limitations

- **Pre-1.0 API**: minor releases may still change public contracts; review
  [STABILITY.md](STABILITY.md) before pinning an extension point.
- **Protection is opt-in**: the five `@RedisCacheable` protection attributes
  default to `false`.
- **Bloom membership is write-populated by default**: it does not scan existing
  data, and an absent bit short-circuits the loader. Seed or maintain the public
  `BloomIFilter` seam before enabling `useBloomFilter` for existing keys.
- **Current artifact gap**: the current Boot 4 / Java 21 line is not yet
  published to Maven Central; `0.0.2` is the historical Boot 3 / Java 17 line.
- **Serializer migration required**: existing Spring-native serialized values
  are not automatically compatible with ResiCache's envelope.
- **Reactive caching is unsupported**: WebFlux `Mono` and `Flux` methods do not
  use the blocking ResiCache interceptor.
- **Async cached methods**: `@Async` methods are not supported for sync-lock and
  Bloom-filter enhancements.
- **Protection switches are startup-only**: changing `resi-cache.protection.*`
  requires an application restart.
- **Time-to-idle reads**: the native writer path intentionally does not refresh
  TTL on read, avoiding write amplification.

For the complete, tested boundary list, use
[COMPATIBILITY.md](COMPATIBILITY.md).

## Not in scope

ResiCache deliberately does not implement capabilities that are better owned by
specialized components:

- **Circuit breaking and rate limiting** → [Resilience4j](https://resilience4j.readthedocs.io/)
- **Multi-level local plus remote caching** → [Caffeine](https://github.com/ben-manes/caffeine) for the local tier
- **Reactive caching** → not supported by the current blocking interceptor

## Project layout

```text
ResiCache/
├── src/main/java/io/github/davidhlp/spring/cache/redis/
│   ├── annotation/          # Public ResiCache annotations
│   ├── cache/               # Internal runtime, AOP, chain, operations, assembly
│   ├── chain/               # Stable handler, operation, and result contracts
│   ├── config/              # Auto-configuration and RedisProCacheProperties
│   ├── protection/          # Stable BloomIFilter and LockManager seams
│   └── serialization/       # Serializer and migration contracts
├── src/test/java/            # Unit, contract, and integration tests
├── resicache-bench/           # Standalone JMH benchmark module
├── scripts/ci/                # CI and repository guards
└── docs/adr/                  # Accepted architecture decisions
```

## Development

### Prerequisites

- JDK 21
- Maven 3.x, or the bundled Maven Wrapper
- Docker for Testcontainers-backed Redis and Cluster verification

### Verification commands

```bash
# No-Docker daily path
./mvnw -Punit test -B

# Full Redis/Testcontainers verification
./mvnw clean verify -B

# Separate style and naming gates
./mvnw checkstyle:check -B
bash scripts/ci/check-test-names.sh

# Package without tests
./mvnw clean package -DskipTests -B
```

`./mvnw clean verify -B` enforces the JaCoCo gate of 70% line coverage and 40%
branch coverage. The full development and contribution workflow is documented
in [CONTRIBUTING.md](CONTRIBUTING.md).

## Project status and support

- **Version**: `v0.0.2`; semantic-versioning guarantees are intentionally
  limited before 1.0.
- **Maintenance**: solo-maintained, non-SLA, best-effort support.
- **Change history**: [CHANGELOG.md](CHANGELOG.md)
- **Compatibility policy**: [COMPATIBILITY.md](COMPATIBILITY.md)
- **API stability**: [STABILITY.md](STABILITY.md)
- **Performance baseline**: [PERFORMANCE.md](PERFORMANCE.md)
- **Architecture decisions**: [ADR index](docs/adr/README.md)
- **Contributing**: [CONTRIBUTING.md](CONTRIBUTING.md)

## Security

Do not open public issues for suspected vulnerabilities. Follow the private
reporting process in [SECURITY.md](SECURITY.md).

## License

[MIT License](LICENSE) © 2026 DavidHLP
