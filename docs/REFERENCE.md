# Reference

Use this page for semantic lookup. Exact signatures, defaults, and generated
metadata remain in source and build resources; this page points to them instead
of copying a second API specification.

## Public entry points

| Surface | Exact source | Contract |
|---|---|---|
| `@RedisCacheable`, `@RedisCachePut`, `@RedisCacheEvict`, `@RedisCaching` | `src/main/java/.../annotation/` | annotation names/types and documented semantics are stable in 0.x |
| `RedisCacheAutoConfiguration`, `RedisProCacheProperties` | `src/main/java/.../config/` | auto-configuration and `resi-cache.*` binding surface |
| `CacheHandler`, `ChainObserver`, `HandlerResult`, `CacheResult`, `HandlerOrder`, and related model types | `src/main/java/.../chain/` | handler/observer protocol and typed values in `STABILITY.md` |
| `BloomIFilter`, `LockManager`, `EarlyExpirationMode` | `src/main/java/.../protection/` | `BloomIFilter` and `LockManager` are stable replacement seams; `EarlyExpirationMode` is a public configuration/value enum, not an independent extension seam |
| Serialization error and migration types | `src/main/java/.../serialization/` | operator/migration surface; not a promise for internal serializer classes |

The exact compiled list is pinned by
`src/test/resources/allowlist/public-surface.txt` and
`public-surface-nested.txt`, enforced by `PublicSurfaceContractTest`. Java
visibility outside that list is not an API promise.

## Configuration lookup

All library properties bind under `resi-cache`. Read exact field types and
Java defaults from
`src/main/java/io/github/davidhlp/spring/cache/redis/config/RedisProCacheProperties.java`.
Read additional metadata from
`src/main/resources/META-INF/additional-spring-configuration-metadata.json`.
The main configuration groups are:

- `enabled` (auto-configuration gate, declared by annotation rather than a
  properties field), `default-ttl`, `key-prefix`, and `transaction-aware`;
- `native-annotation-mode` (`SELECTIVE` by default; also `FULL` and `NONE`);
- `protection.enabled` and the flat per-mechanism fields
  `protection.bloom-filter-enabled`, `sync-lock-enabled`,
  `early-expiration-enabled`, and `null-value-enabled` under that same group;
- `resi-cache.bloom.*`, `resi-cache.early-expiration.*`,
  `resi-cache.sync-lock.*`, and `resi-cache.redisson.*`;
- `redis.*` topology/TLS/deployment fields;
- `serializer.*` and operator migration settings;
- per-cache overrides under `caches.*`;
- optional `disabled-handlers` and feature controls defined by the current
  source;
- `resi-cache.metrics.enabled` (`java.lang.Boolean`, default `false`) — the
  metrics opt-in. It has no `RedisProCacheProperties` field: package-private
  `ResolvedMetrics` (`cache/`) reads it in exactly one place and hands every
  caller a non-null metrics seam — the application `MeterRegistry` when the
  property is `true` and such a bean exists, otherwise the single shared
  `DisabledMetricsRegistry`: one stateless sink for the whole JVM that registers
  and retains nothing, so turning metrics off cannot accumulate meter ids or tag
  strings for dynamically named caches, and the same instance serves every
  context that resolves it. Its metadata comes from
  `additional-spring-configuration-metadata.json`.

The typed properties model is validated at binding time; the separate
`enabled` and `metrics.enabled` gates are resolved during assembly. Do not infer
a default from an old
README snippet when the properties class or generated metadata differs.

## Annotation and operation semantics

- `@RedisCacheable` describes read-through caching and its protection policy.
- `@RedisCachePut` describes an explicit write and supplies policy for a
  write-only declaration.
- `@RedisCacheEvict` describes removal/clear operations; only its Spring eviction
  fields are active. Its read/write protection metadata is compatibility-only.
- Bloom capacity is global (`resi-cache.bloom.bit-size` / `hash-functions`). The
  annotation sizing hints are compatibility-only; see `STABILITY.md`.
- `@RedisCaching` groups operations on a method or type. Type-level discovery
  does not apply policy fields to otherwise unannotated methods; add the needed
  method-level declaration when the policy matters.
- In `SELECTIVE` mode, a plain Spring cache annotation stays on the native
  Spring path unless a ResiCache annotation causes the matching operation to be
  adapted. `FULL` and `NONE` change that adaptation boundary; mixed advisors
  require an explicit, tested choice.
- Protection switches are resolved at startup. A global protection-off keeps
  TTL and disables Bloom, sync-lock, early-expiration, and null-value handlers;
  a per-mechanism true cannot re-enable one after global-off.

### Synchronization timeout

`SyncLockTimeout` resolves the same timeout for locking, follower waits, and
local-only write queues. A positive annotation `syncTimeout` uses that many
seconds; `0` uses the internal 10-second fallback; a negative value uses
`resi-cache.sync-lock.timeout` converted through its configured `unit`.
A converted nonpositive global value falls back to 10 seconds. The annotation
default is 10 seconds, so set `syncTimeout=-1` to inherit the global setting.
This is a coordination bound, not a deadline for the business loader.

### TTL resolution precedence

Effective TTL resolves once, in package-private `TtlPolicy` (`cache/`; see
[`ARCHITECTURE.md`](ARCHITECTURE.md)), from two inputs — the first match wins:

1. **Annotation**: method-level `@RedisCacheable`/`@RedisCachePut` `ttl` when
   greater than zero, optionally jittered by `randomTtl`/`variance`. An unset
   attribute is `0` and declares no method-level TTL; `@RedisCacheEvict.ttl()`
   uses the same unset encoding. The annotation is the only declaration that
   can override the configured default.
2. **Duration parameter**: the write-path TTL Spring Data Redis passes from the
   cache-level `resi-cache.default-ttl` (default `30m`, overridable per cache
   under `caches.*.ttl`). This is the only implicit default, and it applies
   whenever no method-level TTL is declared — an annotation without `ttl`, a
   plain Spring `@Cacheable` in `SELECTIVE` mode, or `ttl=0`.
3. **Permanent entry**: a zero, negative, or `null` parameter applies no TTL
   and the entry has no expiry. Spring Data Redis 4.0 has no separate `null`
   case on a write path: `RedisCacheConfiguration`'s default `TtlFunction` is
   `persistent()`, i.e. `Duration.ZERO`, and `entryTtl` rejects `null` — a
   cache configured without expiry therefore produces a zero parameter.

Positive Duration parameters round up to whole seconds (`1ns` and `500ms`
become `1s`; `1500ms` becomes `2s`). The final TTL from annotation seconds,
Duration parameters, or jitter saturates at `Long.MAX_VALUE / 2000` seconds
(`4,611,686,018,427,387s`); jitter never reduces a positive TTL below one second.
Jitter arithmetic uses overflow-safe addition before applying this final cap.
Jitter applies only when a positive annotation TTL wins; `randomTtl=true` with
`ttl=0` does not jitter the cache-level default.
The cap reserves half the signed millisecond range for Redis's epoch clock:
Spring's Duration-to-millisecond conversion and Redis's relative-to-absolute
expiry addition remain representable while the Redis clock is nonnegative and
at most `Long.MAX_VALUE / 2` milliseconds since the epoch (about 146 million
years). This is a conservative supported limit, not Redis's clock-dependent
maximum. Stored `CachedValue.ttl` and the Redis write use the same capped value.

The `ttl` attribute no longer carries an implicit `60`-second default. An
annotated method that does not set `ttl` now expires its entries after the
configured default rather than after `60s`; the change and the migration
options are recorded in [`COMPATIBILITY.md`](../COMPATIBILITY.md).

## Cache operation outcomes

| Operation | Current behavior |
|---|---|
| GET | Redis failure degrades to a miss while retaining internal failure data and logging the failure |
| PUT | typed runtime failure with the original cause on cache-operation failure |
| PUT_IF_ABSENT | typed runtime failure on failure; it does not become an existing/null result |
| CLEAN / `allEntries=true` eviction | typed failure boundary, while SCAN plus batched deletion can be partial/non-atomic |
| REMOVE | observable best-effort removal; removal failure does not throw through the operation |
| read-through with successful loader | returns the loaded value even if write-back fails; the warning is redacted |
| read-through with failed loader | surfaces the Spring `Cache.ValueRetrievalException` path with the documented diagnostic handling |

This table summarizes current source behavior and focused tests; update it when
the operation contract changes.

## Serialization and compatibility

ResiCache stores an internal `{version, payload}` envelope through
`SecureJacksonRedisSerializer`. It is not wire-compatible with
`GenericJackson2JsonRedisSerializer` or `JdkSerializer`. The whitelist default
and `.*` dot-boundary behavior are defined by `WhitelistPolicy` and the
serializer properties. Literal prefixes use `startsWith`; prefer an explicit
package boundary such as `com.example.dto.*`. Retain `io.github.davidhlp` for
internal cached-value metadata when supplying a replacement list. Application
packages are not derived automatically. An empty list can reach the startup
guard's warning at `ApplicationReadyEvent`. A `null` list instead fails
serializer construction (`WhitelistPolicy` calls `List.copyOf(null)`),
preventing the default runtime from starting before that event.
Polymorphic typing is off by default. The serializer uses
Jackson 2 (`com.fasterxml.jackson`); a host Jackson 3 mapper (`tools.jackson`)
does not replace its Jackson 2 fallback.

JDK legacy migration rejects inputs larger than 16 MiB and limits deserialization
to depth 64, 100,000 references, and arrays of at most 1,000,000 elements before
allocation. These resource limits supplement the class whitelist and preserve
any host input filter. Oversized legacy entries must be regenerated through the
current serializer instead of migrated with the JDK decoder.
With `serializer.fail-on-unknown-type=false`, ordinary JSON decoding failures
return a miss; whitelist violations remain fail-fast regardless of this setting.
WARN reports only exception types, with detailed exceptions at DEBUG.

Use the bounded shadow-read → dual-write → cutover migration described in
[`COMPATIBILITY.md`](../COMPATIBILITY.md) and [`OPERATIONS.md`](OPERATIONS.md).
Do not claim that an in-place serializer swap, a cache flush, or a historical
Maven Central artifact proves compatibility with the current line.

## Errors and diagnostics

Binding validation reports concrete property paths. Missing distributed lock
support is a fail-closed runtime boundary unless local-only degradation is
explicit. WARN/ERROR output and typed failure messages omit raw keys; the current
key-privacy contract and its ownership are documented in [`ARCHITECTURE.md`](ARCHITECTURE.md).
The failure metric is an internal bounded diagnostic, not a public per-key alerting
API.

## Compatibility and stability references

- [`STABILITY.md`](../STABILITY.md): stable 0.x caller-observable surfaces,
  handler/observer protocol, nested public types, and 1.0 markers.
- [`COMPATIBILITY.md`](../COMPATIBILITY.md): supported Boot 4 / Java 21 line,
  Redis/Redisson boundaries, serialization migration, and known limitations.
- [`CHANGELOG.md`](../CHANGELOG.md): versioned changes and breaking markers.
- [`ARCHITECTURE.md`](ARCHITECTURE.md): current ownership and design constraints.
