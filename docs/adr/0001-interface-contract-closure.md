# ADR-0001: ResiCache interface contract closure

**Date**: 2026-09-04  
**Status**: accepted  
**Deciders**: ResiCache maintainers

This ADR records the contract decisions that govern the current Boot 4 / Java
21 line. Verification status belongs to the repository's current CI and local
check results; it does not change the decisions below.
## 1. Cache read/write failure semantics

**Context**: The chain returned failure results without causes, and the Writer
ignored failures for writes and cleanup. That made Redis errors look like
successful cache operations.

**Decision**: GET returns a graceful miss while retaining failure status, kind,
and cause internally and logging the failure. PUT, PUT_IF_ABSENT, and CLEAN
fail fast through one typed internal `CacheOperationException` with the
original cause. REMOVE is observable best-effort and does not throw.

**Alternatives**: Make every operation best-effort (rejected because writes
would be reported as success); add a configurable dual mode (rejected because
it doubles behavior and test surface).

**Consequences**: Write callers can now receive an exception. GET preserves
safe loader fallback. REMOVE can leave stale data, but the failure is logged.

**Known limitation**: CLEAN is SCAN plus batched UNLINK/DEL and can be partial.
**Re-evaluate**: after an adopter reports a consistency incident or requires a
new operation-specific policy.

## 2. GET miss and write consistency

**Context**: A miss and a Redis error both produced null-like results.

**Decision**: `CacheResult` distinguishes `MISS`, `HIT`, `INSERTED`, `EXISTING`,
and `FAILURE`; PIFA failure never becomes an existing/null result.

**Alternatives**: Infer state from nullable bytes (rejected because inserted,
existing-null, and failure collide).

**Consequences**: Internal callers can audit state without changing Spring's
nullable byte API.

**Known limitation**: Spring's `putIfAbsent` byte return still exposes null
for both inserted and an existing entry without bytes. **Re-evaluate** if the
underlying Spring contract exposes a richer result.

## 3. Metadata context propagation

**Context**: The old async wrapper captured metadata on the worker thread and
used a default-resolver `instanceof` check for restore.

**Decision**: Capture on the submitting thread. Restore through the resolver
contract, use LIFO `ScopedActivation`, and restore the worker's prior MDC and
ThreadLocal state in `finally`. Custom resolvers use the same lifecycle.

**Alternatives**: Keep a static default resolver hook (rejected because custom
resolvers cannot restore); clear all MDC blindly (rejected because unrelated
worker context would be lost).

**Consequences**: retrieve/store preserve method metadata across worker reuse
without leaking state.

**Known limitation**: Reactive context propagation is out of scope.
**Re-evaluate**: only with a real reactive adopter and a nonblocking design.

## 4. Async retrieve/store

**Context**: Spring Data Redis 4 supports async writer paths, but the cache
operation metadata must cross the common-pool boundary.

**Decision**: The Writer captures `MethodSnapshot` and MDC before submitting
retrieve/store work. The resolver owns activation and cleanup; the Writer does
not know ThreadLocal implementation details.

**Alternatives**: Disable async support (rejected because the existing writer
contract supports it); put propagation in each handler (rejected because it
duplicates lifecycle logic).

**Consequences**: async failures complete their future exceptionally, and
worker cleanup is paired.

**Known limitation**: The default path still uses the JVM common pool.
**Re-evaluate**: when an executor injection contract is required by a real
adopter.

## 5. Public SPI admission

**Context**: Java visibility exceeds the supported API promise, and one-method
callbacks were mistaken for extension points.

**Decision**: Only documented annotations, configuration keys, wire format,
and behavior-tested deep SPIs are supported. A new public type requires a real
production implementation, a real consumer, a concrete change point, failure
and lifecycle semantics, and a second-adapter contract test.

**Alternatives**: Freeze every public declaration (rejected because it freezes
implementation details); use Javadoc alone (rejected because it has no gate).

**Consequences**: public implementation types remain unstable during 0.x.

**Known limitation**: External usage cannot be proven from this repository.
**Re-evaluate**: before 1.0 and whenever an adopter supplies an implementation.

## 6. Auto-configuration back-off

**Context**: Root-package component scanning registered defaults and migration
components implicitly, while concrete injection defeated replacement.

**Decision**: Remove root-package scanning. The public auto-configuration
scans only the package-private `cache` runtime module (excluding test classes),
while stable defaults retain typed `@ConditionalOnMissingBean` contracts.
`NullValuePolicy` is the shared Actual/Null handler dependency. Bloom's default
is one explicit local-plus-Redis composition replaced by one user `BloomIFilter`
bean. Redisson lock creation remains behind its class-level optional
configuration.

**Alternatives**: Keep scanning (rejected because registration is implicit);
use bean names or `@Primary` as the user override mechanism (rejected because
names and ordering are not a stable contract). Internal infrastructure
qualifiers for the secure Redis template and internal executor are not user
override mechanisms; they prevent collisions with host-provided generic beans
while typed conditions govern supported replacements.

**Consequences**: internal implementation beans are isolated behind one
package boundary; migration components remain operator-only and are not normal
application beans.

**Known limitation**: The targeted ApplicationContextRunner and reflection
checks are green; a broader external-environment matrix remains a CI concern.

## 7. Public API stability

**Context**: The project is pre-1.0 and the current clone has no release tag.
Maven Central publishes `io.github.davidhlp:ResiCache` 0.0.1–0.0.5, 0.0.7,
and 3.2.4; all of them (verified 2026-09-05 from the published POMs) are
the earlier Spring Boot 3.2.4 / Java 17 / Redisson 3.17.6 line, not the
current Boot 4 / Java 21 line. A bounded public search the same date found
no external consumers of any line (one first-party usage example only;
private adopters unprovable).

**Decision**: Keep the documented annotation/configuration/wire surfaces
stable and make implementation types package-private in the internal `cache`
module. The compiled public-surface allowlist is the machine gate; no release,
binary-compatibility, or old-consumer migration gate is enabled.

**Alternatives**: Treat the old Central artifact as a same-line baseline
(rejected because its Boot/Java line and provenance do not match); delete types
immediately (rejected because adopter use is unknown).

**Consequences**: accidental implementation imports fail at compile time and
the report-only comparison remains out of scope until a matching release is
requested.

**Known limitation**: A same-line release tag and artifact are still required
before enabling a blocking Revapi/Japicmp gate. **Re-evaluate** after a
same-line published artifact and matching tag are verified.

## 8. Integration test lifecycle

**Context**: Failsafe was declared without executions, so four `*IT.java`
classes were omitted while most Testcontainers tests ran under Surefire.

**Decision**: Use Surefire for the current integration model, rename the four
classes to `*IntegrationTest.java`, remove dead Failsafe configuration, and
run a naming guard in CI.

**Alternatives**: Move all integration tests to Failsafe (rejected because it
would reclassify many existing tests and risk duplicate/report drift).

**Consequences**: one local/CI report location and no known omitted `*IT`
classes.

**Known limitation**: Docker/Testcontainers lifecycle is verified locally with
Redis 7-alpine; a separate cross-platform Docker daemon matrix remains
unverified. Re-evaluate if test cost requires a separate profile.

## 9. Reactive no-go

**Context**: The interceptor is blocking and no real reactive adopter,
architecture, or CI matrix exists.

**Decision**: Reactive (`Mono`/`Flux`) is unsupported and not part of this
closure.

**Alternatives**: Add a partial reactive adapter (rejected because it would be
pseudo-support).

**Consequences**: scope remains one blocking Boot 4 line.

**Known limitation**: WebFlux methods bypass ResiCache. **Re-evaluate** only
with adopter demand, nonblocking design, maintenance budget, and independent
compatibility CI.

## 10. AOT/native entry conditions

**Context**: Reflection, metadata, and serialization risks were not fully
assessed and no RuntimeHints are present.

**Decision**: AOT smoke is conditional on the four core contract gates and
public boundary closure. Full native certification is deferred. No hints or
native support claim is added now.

**Alternatives**: Add speculative RuntimeHints (rejected because it could mask
contract defects and claim unsupported behavior).

**Consequences**: JVM support remains the only verified target line.

**Verification note**: The local
`mise x java@temurin-21.0.12+101.0.LTS -- ./mvnw spring-boot:process-aot
-DskipTests -B` smoke passed on JDK 21.0.12.1. The `native-image` probe exited
127 with `native-image: unavailable`; this validates JVM AOT processing only
and does not establish native-image support.

**Known limitation**: Native behavior is unverified. **Re-evaluate** after a
real adopter, reflection inventory, and passing native-image validation.

## 11. Refresh executor boundary

**Context**: `EarlyExpirationHandler` needs submit, while `ActualCacheHandler`
needs only cancellation. `RefreshCancellation` cannot represent full executor
lifecycle.

**Decision**: `ThreadPoolEarlyExpirationExecutor` is an internal implementation
retained for construction compatibility. No public submit interface is added;
`RefreshCancellation` remains the narrow cross-package cancel seam. Executor
cleanup and shutdown stay internal.

**Alternatives**: Expose submit/cancel/retry/cleanup/shutdown as a public SPI
(rejected because there is no second production adapter or adopter contract);
make Actual depend on the concrete executor (rejected because implementation
shape leaks into the chain).

**Consequences**: the extension surface stays small and the chain depends only
on cancellation.

**Known limitation**: Replacing the full executor is not a supported public
contract. **Re-evaluate** with a second real executor adapter and lifecycle
contract tests.

## 12. Bloom CLEAN semantics

**Context**: Treating Bloom as a cache-membership index made ordinary CLEAN
clear bits and depend on a failure-prone rebuilding marker and TTL window.

**Decision**: CLEAN removes cache entries only; it never clears Bloom. Bloom
represents possible data-source membership, so retained bits can cause only
false-positives. Rebuilding markers, marker TTLs, and
`rebuild-window-seconds` are removed.

**Consequences**: CLEAN no longer requires distributed rebuilding state, and
Bloom or Redis failures fail open so a valid loader remains executable.

**Known limitation**: An explicit data-source Bloom rebuild operation is not
part of the cache eviction contract.

## 13. Read-through write-back failure contract

**Context**: `get(key, loader)` spans three phases — cache read, loader
(data source), and cache write-back. Previously the sync path merged loader
and write-back exceptions into a single `LoadFailed`, and the default path
(let Spring's `RedisCache.get(key, loader)` drive the writer's 5-arg `get`)
wrapped a write-back failure so the already-loaded business value was lost or
turned into a `ValueRetrievalException`.

**Decision**: Availability-first. The loader's successful value is the
read-through result and is never overridden by a write-back failure:

- **Default path** (`RedisProCacheWriter.get(name, key, supplier, ttl, tti)`):
  cache read (chain GET) → miss → loader → write-back (chain PUT). Loader
  exceptions propagate unchanged (Spring wraps into
  `Cache.ValueRetrievalException`); write-back failures are logged (redacted,
  no raw key) and the loaded bytes are returned.
- **Sync path** (`LoaderOrchestrator.performLockedLoad`): double-check →
  loader → write-back are separate phases. A write-back failure produces
  `LoadOutcome.LoadedWithWriteBackFailure(value, cause)`; `RedisProCache`
  logs redacted and returns the value.
- Explicit `PUT` / `PUT_IF_ABSENT` / `CLEAN` remain fail-fast typed
  (`CacheOperationException`); `REMOVE` stays observable best-effort.

**Consequences**: cache read failures degrade to miss; a cache write failure
never discards a successful loader result. Downstream caches may be stale
until the next write, which is observable only through the (future) failure
metric and redacted logs.

**Known limitation**: The cache is a derived acceleration layer, not the
source of truth — eventual consistency after a failed write-back is accepted.
A per-write retry/backoff policy is not part of this contract.

## 14. Configuration binding validation

**Context**: `RedisProCacheProperties` had only partial local constraints
(root `@Validated`, `defaultTtl @NotNull`, Redisson `@Min`s). Nested
properties lacked `@Valid` cascade; numeric fields (thread pools, ports,
bloom sizes, sync timeout) lacked bounds; cross-field relationships (Redis
deployment mode → host/cluster/sentinel; `tlsRequired` → `tlsEnabled`) had no
binding-time check; and bloom parameters bypassed the properties model via
raw `@Value` reads.

**Decision**: All nested property groups are `@Valid @NotNull` cascaded;
numeric fields carry Jakarta `@Min`/`@Max` bounds; cross-field rules are a
class-level `@RedisDeploymentValidator` that binds violations to the concrete
property node; `resi-cache.bloom.*` moved into `RedisProCacheProperties.Bloom`
and the `bloomFilterConfig` bean consumes the bound properties instead of
`@Value`.

**Consequences**: Invalid configuration fails once at binding time with a
full property path (`resi-cache.bloom.bitSize`, `redis.clusterNodes`,
`redis.tlsEnabled`), not at first runtime use. Defaults boot clean.

**Known limitation**: The validator runs at `@ConfigurationProperties`
binding; programmatic `new RedisProCacheProperties()` mutation after bind is
not re-validated.

## 15. Failure metrics and key privacy

**Context**: Failures were scattered across `CacheErrorHandler`, Bloom and
read-through paths, counted mainly by logs; WARN/ERROR and exception messages
carried raw keys.

**Decision**: A single internal `CacheFailureReporter` (not public, not in the
allowlist) exposes one metric `resicache.cache.failure` tagged only by finite
enums `operation`, `kind`, `strategy`. `CacheErrorHandler` is the single
count-once exit for all chain failures. WARN/ERROR and typed exception
messages omit the raw key; `cacheName` (config-level, low cardinality) is kept
for correlation. `CacheOperationException` carries no raw-key field/getter.

**Consequences**: GET degrade, write fail-fast, REMOVE best-effort and
read-through write-back failures are alertable by bounded tags. The Bloom
filter's own `bloomsift.*` counters and fail-open paths are deliberately
*not* routed here — fail-open is a successful protection behavior, not a
cache-operation failure, so reporting it would corrupt degradation alerts.

**Known limitation**: No per-key alerting; correlation relies on MDC
requestId.

## 16. AOT/native deferred

**Context**: Spring Boot `process-aot` was observed starting the
`SerializationMigrationCli` rather than the host auto-configuration — not a
host-config smoke. No `spring-boot-maven-plugin` AOT wiring exists in the pom.

**Decision**: AOT/native-image compatibility is recorded **DEFERRED**
(non-blocking debt per plan P2-AOT-001). The serialization envelope
(`{version, payload}` + whitelist) is a known native-image reflection
surface that would need explicit `RuntimeHints` before GraalVM support is
claimed.

**Consequences**: No native-image claim in README/STABILITY. Revisit when a
real native deployment requirement appears.

## 17. Internal runtime module closure

**Context**: The public-surface Gate still accounted for 86 implementation
types as in-progress because their source packages exposed Spring adapters,
operation builders, protection policies, metadata, and serializer details.

**Decision**: Move the implementation collaborators into the package-private
`io.github.davidhlp.spring.cache.redis.cache` runtime module and physically
co-locate their tests. `RedisCacheAutoConfiguration` scans only that internal
package (excluding test classes); stable SPI types keep their original package
names. The wire envelope remains `serialization.VersionEnvelope`, and
`CacheContext` exposes only `InputView`/`CachePolicyView`.

**Verification**: The compiled public surface now exactly equals the 34-entry
allowlist; the in-progress manifest is empty. `clean verify` passes 916 tests,
coverage checks, Checkstyle, and Javadoc with zero warnings.
