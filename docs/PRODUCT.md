# Product

This document describes the current product boundary of the checked-out
ResiCache source line. It is not a roadmap and does not promise behavior that
is only proposed or unverified.

## Purpose

ResiCache adds protection-in-depth to Spring Cache backed by Redis. It keeps
Spring Cache as the application-facing model and composes cache protection in a
typed responsibility chain rather than introducing a second application AOP
model.

The product is intended for application developers who need explicit controls
for common Redis cache failure modes, and for operators who need those controls
to fail visibly instead of silently claiming guarantees they do not have.

## Current capability set

- **Bloom membership protection** for known data-source membership.
- **Synchronized loading** through a distributed `LockManager`, with an
  explicit local-only fallback for single-JVM deployments.
- **TTL jitter** to spread expiration boundaries.
- **Null-value caching** when enabled.
- **Early expiration** for hot-key refresh.
- **Spring Cache annotations and cache operations** through the library's
  auto-configuration.
- **Controlled serialization** using an internal `{version, payload}` envelope
  and a deserialization package allowlist.
- **Optional metrics and health integration** when the relevant application
  infrastructure is present.

Protection attributes are opt-in. The current default annotation mode is
`SELECTIVE`, so a plain Spring `@Cacheable` method does not automatically gain
ResiCache protection unless the application chooses another supported mode.
See [`REFERENCE.md`](REFERENCE.md) for semantic details.

## Terms and roles

- **Protection chain**: ordered handlers that evaluate a cache operation before
  the actual Redis operation.
- **Membership bit**: Bloom-filter state describing possible data-source
  membership, not current cache-entry presence.
- **Native annotation path**: Spring's cache annotations adapted according to
  `native-annotation-mode`.
- **ResiCache annotation path**: `@RedisCacheable`, `@RedisCachePut`,
  `@RedisCacheEvict`, and `@RedisCaching` declarations.
- **Application developer**: supplies Spring Cache usage, configuration, and
  any custom handler or stable seam implementation.
- **Operator**: supplies Redis topology, trusted secrets/configuration, rollout
  and migration control, and observability infrastructure.
- **Contributor**: changes the library, tests, documentation, or CI within the
  repository contracts.

## Main flows

### Application startup

The application enables Spring Cache. ResiCache's auto-configuration is then
loaded when the Redis classes are available and `resi-cache.enabled` is not
false. Configuration is validated during binding; the protection chain is
assembled once and configuration changes require a restart.

### Protected cache operation

A protected ResiCache operation, or a native Spring operation adapted by the
selected `native-annotation-mode`, is resolved into the library's operation
model. In `SELECTIVE` mode, an otherwise plain Spring cache annotation remains
on Spring's native path. The internal chain then evaluates Bloom,
synchronization, early expiration, TTL, null-value, and actual-cache behavior
in the order defined by `HandlerOrder`. The public extension contracts are
intentionally narrower than the internal implementation module.

### Read-through loading

A successful loader value remains available even when the cache write-back
fails. Loader failures remain observable as the documented Spring failure. This
availability-first behavior is not a general retry guarantee; the cache is a
derived acceleration layer. Exact operation semantics are in
[`REFERENCE.md`](REFERENCE.md), and the implementation boundary is in
[`ARCHITECTURE.md`](ARCHITECTURE.md).

### Serializer migration

The internal envelope is not wire-compatible with Spring's generic JSON or JDK
serializer. Existing applications must use a bounded shadow-read,
dual-write, and cutover process before relying on ResiCache values. The
migration tooling is operator-directed and is not run automatically at
application startup.

## Product rules

- The supported runtime is the blocking Spring Cache integration. Reactive
  `Mono`/`Flux` caching is outside the current product boundary.
- Missing distributed-lock support with `sync=true` fails closed by default;
  `local-only=true` is an explicit single-JVM degradation, not a cluster
  guarantee.
- Bloom state is populated by successful writes by default; the library does
  not rebuild it from the data source. A missing bit can short-circuit a load,
  so existing keys require a seed/maintenance strategy before enabling Bloom.
- `protection.enabled=false` disables the four protection handlers but keeps
  the TTL handler. Per-mechanism values cannot re-enable protection after the
  global switch is off.
- A configuration change to protection switches is startup-only and requires a
  restart; no dynamic chain rebuild is supported.
- The current build line is pre-1.0, non-SLA, and source-first. Version and
  runtime boundaries belong to [`COMPATIBILITY.md`](../COMPATIBILITY.md), not to
  this summary.

## Non-goals

ResiCache does not replace:

- circuit breaking or rate limiting (use a resilience component);
- a multi-level local-plus-remote cache (use a cache tiering component);
- a reactive cache interceptor;
- a hosted cache service, deployment controller, backup service, or guaranteed
  support operation.

## Approved but not implemented

AOT/native-image certification remains deferred. Its entry conditions remain
in the local task ledger; this product document makes no native support claim.
Other open or deferred work is owned by the existing task ledger, not by a new
roadmap document.
