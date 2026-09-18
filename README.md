# ResiCache

**Protection-in-depth for Spring Cache on Redis.** ResiCache adds explicit
cache-penetration, cache-breakdown, cache-avalanche, and hot-key refresh
protection while keeping Spring Cache as the application-facing model.

[![CI](https://github.com/davidhlp/ResiCache/actions/workflows/ci.yml/badge.svg)](https://github.com/davidhlp/ResiCache/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

[English](README.md) · [简体中文](README.zh-CN.md) ·
[Documentation map](docs/README.md)

> [!WARNING]
> ResiCache is pre-1.0 (`v0.0.2`), non-SLA, and currently maintained by one
> maintainer. The current source line targets Spring Boot 4.0 and Java 21, but
> it does not have a matching Maven Central artifact. Read the
> [compatibility matrix](COMPATIBILITY.md) before adopting it.

## What it provides

| Capability | Purpose |
|---|---|
| Bloom filter | Avoids loading keys that are known not to exist |
| Distributed lock | Coordinates concurrent loads with Redisson or another `LockManager` |
| TTL jitter | Spreads expiration boundaries |
| Null-value caching | Retains negative lookups when explicitly enabled |
| Early expiration | Refreshes hot keys before normal expiry |
| Responsibility chain | Orders protection through `HandlerOrder` |
| Secure serialization | Uses a whitelisted `{version, payload}` envelope |
| Spring Cache integration | Reuses Spring's cache API and `@EnableCaching` boundary |

Protection is opt-in. The default `native-annotation-mode` is `SELECTIVE`, and
the five protection attributes on `@RedisCacheable` default to disabled. The
current product boundary and non-goals are in [`docs/PRODUCT.md`](docs/PRODUCT.md).

## Compatibility

The supported repository line is:

- Spring Boot 4.0.0 / Spring Framework 7 / Spring Data Redis 4.0.x
- Java 21
- Redis 7.x
- Redisson 3.50.0 when distributed synchronization is needed
- Caffeine 3.1.8 for internal support

Boot 3.x is not a maintained compatibility line. The complete matrix,
serialization migration boundary, failure semantics, and known limitations are
in [`COMPATIBILITY.md`](COMPATIBILITY.md).

## Quick start

The current line is source-first. Build and install the checkout for a local
consumer; this does not publish anything:

```bash
git clone https://github.com/davidhlp/ResiCache.git
cd ResiCache
./mvnw -Punit test -B
./mvnw install -DskipTests -B
```

Use the coordinates and version from the checkout's root `pom.xml` in the
consumer application. Do not treat the historical Maven Central `0.0.2`
artifact as the current Boot 4 line.

Configure the two Redis clients explicitly when using synchronization:

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
resi-cache:
  redis:
    mode: single
    host: localhost
    port: 6379
    database: 0
```

The Spring Data Redis namespace supplies cache I/O. The `resi-cache.redis.*`
namespace supplies the Redisson deployment used by distributed locking; the
namespaces are not implicitly copied into one another. The application remains
responsible for enabling Spring Cache:

```java
import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableCaching
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    UserService userService() {
        return new UserService();
    }

    public static class UserService {
        @RedisCacheable(value = "users", key = "#id")
        public String getUserById(Long id) {
            return "user-" + id;
        }
    }
}
```

The cacheable method is intentionally self-contained so the snippet can be
copied into a small Boot application without inventing a repository type.

`sync=true` fails closed if no distributed lock is available unless the
application explicitly opts into `resi-cache.sync-lock.local-only=true` for a
single-JVM deployment. Configure a serializer allowlist for application value
packages before reading custom cached types.

## Where to go next

| Question | Canonical page |
|---|---|
| What is in scope, and what is not? | [`docs/PRODUCT.md`](docs/PRODUCT.md) |
| How are modules and handlers connected? | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| What are annotation, configuration, and error semantics? | [`docs/REFERENCE.md`](docs/REFERENCE.md) |
| How do I build, test, or debug? | [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) |
| How do I operate, migrate, or release? | [`docs/OPERATIONS.md`](docs/OPERATIONS.md) |
| Which public types are stable? | [`STABILITY.md`](STABILITY.md) |
| Which runtime versions are supported? | [`COMPATIBILITY.md`](COMPATIBILITY.md) |
| What changed? | [`CHANGELOG.md`](CHANGELOG.md) |
| How do I contribute? | [`CONTRIBUTING.md`](CONTRIBUTING.md) |
| How do I report a vulnerability? | [`SECURITY.md`](SECURITY.md) |

## Deliberate non-goals

ResiCache does not implement circuit breaking, rate limiting, a multi-level
local-plus-remote cache, reactive caching, a hosted cache service, or a
production backup/deployment controller. Use the appropriate platform or
specialized component for those responsibilities.

## Project status and support

- **Version:** `0.0.2`; pre-1.0 compatibility is limited to the documented
  stability contract.
- **Maintenance:** solo-maintained, non-SLA, best-effort support.
- **History:** [`CHANGELOG.md`](CHANGELOG.md).
- **Benchmarks:** historical, non-SLO evidence in [`PERFORMANCE.md`](PERFORMANCE.md).
- **License:** [`LICENSE`](LICENSE).

The repository's exact configuration model is defined by
`RedisProCacheProperties` and its generated metadata, not by a manually copied
configuration table in this README.
