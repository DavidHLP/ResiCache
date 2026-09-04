# ResiCache API Stability Contract

> **Status:** pre-1.0 (0.x development line).
> This document is the canonical answer to *"what is stable and what isn't?"*
> during the 0.x cycle. It supersedes the pre-1.0 caveat previously buried
> in [`CHANGELOG.md`](./CHANGELOG.md).

Adopting teams can pin to a 0.x version with confidence that the surface
described in §1 and §3 will not break in patch releases.

---

## 1. Public API surface (stable across all 0.x versions)

The following are **stable** and will not change in any 0.x release without
a documented migration path (⚠️ BREAKING entry in
[`CHANGELOG.md`](./CHANGELOG.md)):

| Surface | Stable form | Notes |
|---------|-------------|-------|
| **Enhancement annotation signatures** | `@RedisCacheable`, `@RedisCachePut`, `@RedisCacheEvict`, `@RedisCaching` | Attribute names, types, and semantics. Adding new attributes is non-breaking. |
| **Configuration property keys** | `resi-cache.*` namespace under `application.yml` / `application.properties` | Property names and types. Adding new properties is non-breaking. |
| **Wire format** | `{version, payload}` envelope used by `SecureJacksonRedisSerializer` | Envelope is the serialization contract — kept, not loosened. |
| **Extension SPI** | `CacheHandler`, `ChainObserver`, `BloomIFilter`, `LockManager`, `LockManager.LockHandle`, `HandlerPriority` | Implementations must satisfy the documented failure, lifecycle, and thread-safety contracts. |
| **SPI transitive contract types** | `CacheContext`, `HandlerResult`, `CacheResult`, `CacheOperation`, `FlowControl`, `HandlerOrder`, and decision records used by handler signatures | These signature/value types and the `HandlerOrder` numeric ordering contract are part of the supported SPI surface; unrelated fields and implementation classes remain unstable. |

If you pin to a specific 0.x.y version, these are guaranteed within the 0.x
line.

## 2. What may change in 0.x (without a major version bump)

| Area | What may change | Example |
|------|-----------------|---------|
| **Internal implementation** | Source-level details inside `chain/`, `protection/`, `cache/` | Handler ordering is fixed by `HandlerOrder` enum (gap = 100), but inner algorithm of a specific handler is not contractual |
| **Default values of properties** | Defaults may be tuned between minor versions | `resi-cache.default-ttl` default may shift toward a better baseline |
| **Unstable package layout** | Contents of internal sub-packages and unstable implementation types under `io.github.davidhlp.spring.cache.redis.*` | Stable annotations, configuration keys, wire format, and SPI signature types listed in §1 are excluded. |
| **Observability metric names and tags** | Pre-1.0 metric namespace is NOT contractual | A `bloomsift.*` → `resicache.handler.*` rename is allowed pre-1.0 (with ⚠️ BREAKING CHANGELOG) |
| **Diagnostic warnings and logs** | Message text, log levels for startup probes | "whitelist auto-derived from host app root package" WARN may rephrase |
| **Behavior defaults** (e.g. protection preset) | When explicitly opted into a new default via ⚠️ BREAKING CHANGELOG entry | `resi-cache.protection.preset=NONE` (v0.0.2) → `=STANDARD` (v0.0.3) is allowed if flagged breaking |
| **Unstable public implementation and provisional strategy types** | `TtlPolicy`, `NullValuePolicy`, `EarlyExpirationPolicy`, `BloomHashStrategy`, `MethodMetadataResolver`, `MethodSnapshot`, `ScopedActivation`, `RefreshCancellation`, `LoaderOrchestrator`, `LoadOutcome`, `DefaultLoadFn`, default adapters, and `ThreadPoolEarlyExpirationExecutor` | Public for current source compatibility; not supported extension contracts unless listed in §1. |

If you depend on items in this section, pin to an exact patch version
(`0.x.y`) and review `CHANGELOG.md` entries on upgrade.

### Accidental public type migration

| Type | Replacement | Deprecation/removal | Impact |
|---|---|---|---|
| `MethodMetadataResolver` / `MethodSnapshot` | internal resolver lifecycle via auto-configuration | deprecate after external-usage review; internalize after one minor release | source/binary break for custom resolver implementations |
| `LoaderOrchestrator` / `LoadOutcome` / `DefaultLoadFn` | `RedisProCache.get(key, loader)` | deprecate after external-usage review; remove after one minor release | callers must use the cache API, not loader callbacks |
| `CacheContext` / `HandlerResult` / decision records | documented SPI value surface for handler signatures; implementation-only members may evolve | no removal while `CacheHandler`/`ChainObserver` remain supported | extensions use documented fields and flow values |
| default policy and executor classes | `TtlPolicy`, `NullValuePolicy`, and `EarlyExpirationPolicy` contracts; executor remains internal | no concrete support promise; remove concrete access after replacement evidence | custom code uses policy interfaces, not executor classes |

Removal is not activated solely from local source evidence. A published
artifact, adopter usage, or external implementation supersedes this default
plan and changes the migration decision.

## 3. What will NOT change without a major version bump

- `@RedisCacheable`, `@RedisCachePut`, `@RedisCacheEvict`, `@RedisCaching`
  attribute names and types
- `resi-cache.*` property keys
- The `{version, payload}` envelope wire format

These are the absolute minimum a downstream user needs to upgrade between
0.x patch versions without code changes.

## 4. 1.0 graduation (forward markers — pre-1.0)

Graduation to 1.0 is a pre-1.0 milestone not yet reached. The markers below
describe *what 1.0 will mean* and are aspirational until the `1.0.0` tag is cut:

1. **Public surface stability** — §1 + §3 have held across at least one
   release cycle without breaking changes.
2. **Production-grade ops surface** — Maven Central publish under
   `io.github.davidhlp`, CycloneDX SBOM per release, OWASP dependency-check
   gate at HIGH/CRITICAL.
3. **Adoption signal** — at least one production adopter listed in
   `ADOPTERS.md` (created when the first adopter lands).
4. **Bus factor** — a named successor or a documented succession plan
   (see [`CONTRIBUTING.md`](./CONTRIBUTING.md) → *Maintainers & bus factor*).

When 1.0 ships, items in §2 (internals, defaults, metric names) become
**locked** — any change is a new major version (2.0). Until then, §2 keeps
those areas open for tuning.

## 5. How to read this document

- Pin to **exact `0.x.y`** versions if you depend on §2 behavior.
- Pin to **`0.x`** (minor-flexible) if you depend only on §1 + §3.
- Pin to **`1.x`** (or migrate when it lands) if you want SemVer
  guarantees on metric names, log messages, and behavior defaults.

---

## 6. References

- [`CHANGELOG.md`](./CHANGELOG.md) — per-version changelog including
  ⚠️ BREAKING markers.

> Historical architecture decisions live in `git log` (commit bodies capture
> rationale + alternatives rejected). No wiki ADR prose is consulted.