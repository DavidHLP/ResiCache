# Architecture

This is the current implementation map for the Boot 4 / Java 21 source line.
The source tree, tests, public-surface allowlists, and retained contract
documents are the evidence sources; this document summarizes ownership and
constraints without replacing them.

## System boundary

```text
Host application
  ├─ Spring Cache annotations / Cache API
  ├─ application configuration
  └─ optional handler or stable-seam beans
        │
        ▼
RedisCacheAutoConfiguration
  ├─ binds RedisProCacheProperties
  ├─ scans only the package-private cache runtime
  └─ assembles the proxy, cache manager, writer, chain, serializer, and observers
        │
        ├─ Spring Data Redis cache I/O
        └─ optional Redisson coordination / Redis topology
```

`RedisCacheAutoConfiguration` is conditional on Redis classes and
`resi-cache.enabled`; it does not add `@EnableCaching`. The internal component
scan is deliberately limited to `io.github.davidhlp.spring.cache.redis.cache`
and excludes test classes plus the operator-boundary assembly root, which is
named by class. Runtime bean ownership is never expressed as a name pattern:
classes that only their boundary may register carry no component stereotype,
and a class rename fails compilation instead of silently changing the
assembled set. Host application packages are not scanned by the library.

The operator CLI (`SerializationMigrationCli`) is the second assembly
boundary: its context names the internal migration beans by class through
`SerializationMigrationOperatorConfiguration` and excludes
`RedisCacheAutoConfiguration` by class, so it never assembles the cache/AOP
runtime and needs no enablement gate.

## Module ownership

| Package | Responsibility | Contract status |
|---|---|---|
| `annotation/` | `@RedisCacheable`, `@RedisCachePut`, `@RedisCacheEvict`, `@RedisCaching` | Public annotation surface |
| `config/` | Auto-configuration, bound properties, deployment validation, enablement checks | Public entry/config surface |
| `chain/` and `chain/model/` | Handler, operation, result, flow, continuation, priority, and typed decision contracts | Documented SPI/value surface; see `STABILITY.md` |
| `chain/observer/` | Chain observer hooks and scope-token protocol | Documented observer SPI |
| `protection/` | `BloomIFilter`, `LockManager`, and early-expiration policy/value types | `BloomIFilter` and `LockManager` are stable replacement seams; `EarlyExpirationMode` is a public configuration/value enum, not an independent extension seam |
| `serialization/` and `serialization/migration/` | Envelope/serialization errors and operator migration surface | Public operator/migration contracts; implementation remains internal |
| `cache/` | AOP, operation assembly, chain engine/handlers, Redis writer/manager, refresh, metrics, and serializer wiring | Internal runtime module and non-default extension boundary; only declarations explicitly listed by `STABILITY.md` and the allowlist are supported |
| `src/test/` and `scripts/ci/` | Contract, unit, Redis/Testcontainers, packaged-consumer, and repository guard evidence | Verification, not runtime API |

A public Java declaration is not automatically a supported extension point. The
compiled allowlist and `STABILITY.md` classify the public surface; internal
classes can move or disappear without becoming a compatibility promise.

## Cache execution chain

`HandlerOrder` is the single ordering source. The current sequence is:

| Order | Handler | Responsibility |
|---:|---|---|
| 100 | `BloomFilterHandler` | membership gate / penetration protection |
| 200 | `SyncLockHandler` | distributed or explicit local-only synchronization |
| 250 | `EarlyExpirationHandler` | hot-key refresh decision and scheduling |
| 300 | `TtlHandler` | base TTL and jitter calculation |
| 400 | `NullValueHandler` | negative-result encoding |
| 500 | `ActualCacheHandler` | actual cache operation |

`CacheHandlerChainFactory` discovers internal handlers, orders them through
`@HandlerPriority(HandlerOrder.X)`, applies startup protection switches, and
assembles observers. `ChainEngine` owns advancement, flow decisions, observer
hook ordering, and post-processing isolation. A custom handler must be supplied
by the host application's component scan or as an application bean.

## Annotation and policy flow

The annotation path is intentionally split into two views:

1. `AnnotationParser` creates an immutable parsed snapshot.
2. `RedisCacheOperationSource` validates and adapts Spring-native operations.
3. The final snapshot is registered for policy lookup.
4. `CacheOperationResolver` resolves the policy namespace for the operation.
5. GET uses the read declaration; explicit write-only PUT uses the PUT
   declaration; read-through write-back remains governed by the read side.

The split is required by the Spring operation source and the chain-side policy
resolver. It is not permission to reintroduce per-invocation parsing or to
collapse the two operation representations without a new contract decision.
Class-level operation discovery and method-level policy application retain the
current documented behavior in `COMPATIBILITY.md`.

## Data and failure ownership

- Spring's cache abstraction remains the host-facing boundary.
- `RedisProCache` / `RedisProCacheWriter` own cache-manager and writer behavior;
  `CacheResult` carries typed operation outcomes internally.
- `LoaderOrchestrator` owns the shared read → load → write-back protocol. A
  successful loaded value is returned even when write-back fails.
- `FailureReport` owns the one failure-reporting shape: a WARN/ERROR carrying
  only cacheName or the key fingerprint plus the exception type chain, paired
  with a DEBUG line holding the full stack; `CacheErrorHandler` owns count-once
  reporting for chain failures on top of it, and the failure metric uses finite
  operation/kind/strategy dimensions.
- `SecureJacksonRedisSerializer` owns whitelist-backed serialization and the
  `{version, payload}` envelope. Refresh metadata required by policy/CAS is
  persisted; process-local monotonic time is not.
- Redis is an acceleration layer, not the application source of truth. A
  tolerated write-back failure can leave stale cache state and has no implicit
  retry/backoff contract.

## Supported seams and non-seams

Supported seams include the documented annotations/configuration keys, the wire
format, `CacheHandler`/observer contracts, `BloomIFilter`, `LockManager`, and
related typed value surfaces listed in `STABILITY.md`. The full refresh
executor, internal policies, metadata resolver, serializers' implementation
classes, handler internals, and unlisted declarations in `cache/` are not
supported replacement contracts.

New public types require a real production consumer, a concrete change point,
explicit failure/lifecycle semantics, and a second-adapter contract test. This
follows the documented stability and allowlist boundary; do not create a public
seam for a single implementation or a speculative future use.

## Durable decisions

The rules below summarize current source, tests, and retained contract
documents. Update the owning current-state document when a behavior changes.

| Current rule | Reason / boundary | Evidence |
|---|---|---|
| GET degrades to an observable miss; PUT/PIFA/CLEAN are typed fail-fast; REMOVE is observable best-effort | prevents writes being reported as success while preserving safe loader fallback | `REFERENCE.md`; cache operation tests |
| Read-through loader write-back is availability-first | a cache write failure must not discard a loaded business value | `PRODUCT.md`; loader tests |
| Only internal `cache/` is scanned | avoids implicit host-package registration and keeps implementation hidden | `RedisCacheAutoConfiguration`; auto-configuration tests |
| Protection switches resolve once and global-off wins | no runtime chain rebuild or hot-update contract exists | `CacheHandlerChainFactoryTest` |
| Bloom CLEAN keeps membership bits | membership is not current cache-entry state; stale bits are safe false-positives | `COMPATIBILITY.md`; Bloom tests |
| Reactive caching is unsupported | the interceptor is blocking and no compatible adopter/CI matrix exists | `COMPATIBILITY.md` |
| Native-image support is deferred | RuntimeHints/reflection inventory and native toolchain evidence are absent | task ledger |
| Public surface is allowlist-driven during 0.x | Java visibility alone would overstate compatibility | `STABILITY.md`; allowlist gate |

## Verification anchors

Use the current test and script names rather than copying their implementation:

- `PublicSurfaceContractTest` and `src/test/resources/allowlist/` for the
  packaged public boundary;
- `CacheHandlerChainFactoryTest` for switch precedence and chain assembly;
- `RedisCacheSemanticsIntegrationTest` and the other
  `*IntegrationTest.java` classes for real Redis behavior;
- `RedisClusterSlotIntegrationTest` for cluster lock/data slot co-location;
- `bash scripts/ci/check-test-names.sh` for container-test naming;
- `bash scripts/ci/check-external-consumer.sh` for a packaged-JAR consumer;
- `bash scripts/ci/check-docs-contracts.sh` for documentation/source guards.
