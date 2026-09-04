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

**Decision**: Remove root scanning. Register runtime components through an
explicit import list and register defaults with typed
`@ConditionalOnMissingBean`. `NullValuePolicy` is the shared Actual/Null
handler dependency. Bloom's default is one explicit local-plus-Redis
composition replaced by one user `BloomIFilter` bean. Redisson lock creation
remains behind its class-level optional configuration.

**Alternatives**: Keep scanning (rejected because registration is implicit);
use bean names or `@Primary` as the user override mechanism (rejected because
names and ordering are not a stable contract). Internal infrastructure
qualifiers for the secure Redis template and internal executor are not user
override mechanisms; they prevent collisions with host-provided generic beans
while typed conditions govern supported replacements.

**Consequences**: missing defaults are visible in one configuration class;
migration components are not normal application beans.

**Known limitation**: The targeted ApplicationContextRunner and reflection
checks are green; a broader external-environment matrix remains a CI concern.

## 7. Public API stability

**Context**: The project is pre-1.0 and the current clone has no release tag.
Maven Central does publish `io.github.davidhlp:ResiCache:0.0.2`, but that
artifact is from the earlier Spring Boot 3 / Java 17 line, not the current
Boot 4 / Java 21 line.

**Decision**: Keep the documented annotation/configuration/wire surfaces
stable, label implementation types unstable, and deprecate accidental public
loader/resolver types for one minor release before internalization when no
external promise is found. Use the signed Central artifact only for a
report-only compatibility comparison; do not make that cross-line result a
blocking gate.

**Alternatives**: Treat the old Central artifact as a same-line baseline
(rejected because its Boot/Java line and provenance do not match); delete types
immediately (rejected because adopter use is unknown).

**Consequences**: migration is explicit without guessing binary provenance;
the report-only comparison records the known cross-line breakage.

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
