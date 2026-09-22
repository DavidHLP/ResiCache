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

- `default-ttl`, `key-prefix`, and `transaction-aware`;
- `native-annotation-mode` (`SELECTIVE` by default; also `FULL` and `NONE`);
- `protection.*` global/per-mechanism switches;
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
  property is `true` and such a bean exists, otherwise a shared no-op adapter.
  Its metadata comes from
  `additional-spring-configuration-metadata.json`.

Configuration is validated at binding time. Do not infer a default from an old
README snippet when the properties class or generated metadata differs.

## Annotation and operation semantics

- `@RedisCacheable` describes read-through caching and its protection policy.
- `@RedisCachePut` describes an explicit write and supplies policy for a
  write-only declaration.
- `@RedisCacheEvict` describes removal/clear operations.
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

### TTL resolution precedence

Effective TTL resolves once, in package-private `TtlPolicy` (`cache/`; see
[`ARCHITECTURE.md`](ARCHITECTURE.md)), from three inputs — the first match wins:

1. **Annotation**: method-level `@RedisCacheable`/`@RedisCachePut` `ttl` when
   greater than zero (attribute default `60`), optionally jittered by
   `randomTtl`/`variance`.
2. **Duration parameter**: the write-path TTL Spring Data Redis passes from the
   cache-level `resi-cache.default-ttl` (default `30m`, overridable per cache
   under `caches.*.ttl`). It applies only when no method-level TTL is set —
   `ttl=0`, or a plain Spring `@Cacheable` in `SELECTIVE` mode. A zero or
   negative parameter yields a permanent entry (no expiry).
3. **No TTL context**: a `null` parameter (for example a caller-supplied
   `RedisCacheConfiguration`) falls back to `TtlPolicy`'s `60`-second default.

The consequence that branch 1's `60`-second annotation default overrides
`resi-cache.default-ttl` is recorded as a supported-behaviour limitation in
[`COMPATIBILITY.md`](../COMPATIBILITY.md).

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
serializer properties. Polymorphic typing is off by default.

Use the bounded shadow-read → dual-write → cutover migration described in
[`COMPATIBILITY.md`](../COMPATIBILITY.md) and [`OPERATIONS.md`](OPERATIONS.md).
Do not claim that an in-place serializer swap, a cache flush, or a historical
Maven Central artifact proves compatibility with the current line.

## Errors and diagnostics

Binding validation reports concrete property paths. Missing distributed lock
support is a fail-closed runtime boundary unless local-only degradation is
explicit. WARN/ERROR output and typed failure messages omit raw keys; the current
key-privacy contract and its ownership are documented in [`ARCHITECTURE.md`](ARCHITECTURE.md).
failure metric is an internal bounded diagnostic, not a public per-key alerting
API.

## Compatibility and stability references

- [`STABILITY.md`](../STABILITY.md): stable 0.x caller-observable surfaces,
  handler/observer protocol, nested public types, and 1.0 markers.
- [`COMPATIBILITY.md`](../COMPATIBILITY.md): supported Boot 4 / Java 21 line,
  Redis/Redisson boundaries, serialization migration, and known limitations.
- [`CHANGELOG.md`](../CHANGELOG.md): versioned changes and breaking markers.
- [`ARCHITECTURE.md`](ARCHITECTURE.md): current ownership and design constraints.
