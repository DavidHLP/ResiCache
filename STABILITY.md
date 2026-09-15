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
| **SPI transitive contract types** | `CacheContext`, `HandlerResult`, `CacheResult`, `CacheOperation`, `FlowControl`, `ChainContinuation`, `HandlerOrder`, and decision records used by handler signatures | These signature/value types and the `HandlerOrder` numeric ordering contract are part of the supported SPI surface; unrelated fields and implementation classes remain unstable. |

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
| **Internal implementation types** | `MethodMetadataResolver`, `MethodSnapshot`, `ScopedActivation`, `RefreshCancellation`, `LoaderOrchestrator`, `LoadOutcome`, and `ThreadPoolEarlyExpirationExecutor` | Package-private collaborators under the internal `cache` module; not importable extension contracts. |

If you depend on items in this section, pin to an exact patch version
(`0.x.y`) and review `CHANGELOG.md` entries on upgrade.

### Accidental public type migration

| Type | Replacement | Deprecation/removal | Impact |
|---|---|---|---|
| `MethodMetadataResolver` / `MethodSnapshot` | internal resolver lifecycle via auto-configuration | internalized in the Phase 4 cache module | source/binary break for custom resolver implementations |
| `LoaderOrchestrator` / `LoadOutcome` (and the former `DefaultLoadFn`) | `RedisProCache.get(key, loader)` | internalized in the Phase 4 cache module | callers must use the cache API, not loader callbacks |
| `CacheContext` / `HandlerResult` / decision records | documented SPI value surface for handler signatures; implementation-only members may evolve | no removal while `CacheHandler`/`ChainObserver` remain supported | extensions use documented fields and flow values |
| `ThreadPoolEarlyExpirationExecutor` | documented stable SPI only; concrete executor remains internal | internalized in the Phase 4 cache module | custom code uses stable interfaces, not implementation classes |

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

## 4. Extension SPI protocol (`CacheHandler` / `ChainObserver`)

Implementing the documented SPI means agreeing to the following protocol.
The engine enforces the machine-checkable parts; the rest is the contract a
custom implementation must satisfy.

### Handlers

1. **Non-null result**: handling a node MUST return a non-null
   `HandlerResult`. The engine calls `handle(context, next)` and rejects `null`
   with `IllegalStateException("CacheHandler returned null HandlerResult:
   <class>")` — never an opaque NPE. A handler whose work requires the
   continuation (see item 6) may reject a bare `handle(context)` call with
   `IllegalStateException` rather than silently run a partial chain.
2. **FlowControl semantics**: `CONTINUE` advances to the next handler (a
   `null` result field at chain end materializes to `success()`); `TERMINATE`
   ends the chain and returns the carried result; `SKIP_ALL` ends the chain,
   returns the carried result, and sets the engine-only
   `skipRemaining` marker so no further handler runs.
3. **Post-process**: only handlers whose `requiresPostProcess(context)`
   returns `true` get `afterChainExecution(context, result)` after the main
   chain completes. Exceptions thrown there are caught and logged by the
   engine; they never alter the main-chain result.
4. **Ordering**: `@HandlerPriority(HandlerOrder.X)` is the single source of
   truth (gap = 100). Unannotated handlers sort last.
5. **Thread safety**: one handler instance is shared across concurrent
   executions; keep per-call state out of fields (use `CacheContext`).
6. **Nested advancement (optional)**: the engine calls
   `handle(context, ChainContinuation next)`, whose default ignores `next` and
   delegates to `handle(context)` — existing implementations are unaffected.
   A handler that overrides it may run the remainder of the chain inside its
   own critical section (this is how `sync=true` runs the chain inside the
   distributed lock). The handle is valid only for that call, may be advanced
   at most once (a second call throws `IllegalStateException`), stays on the
   calling thread, and carries per-node observation only — around-chain
   observation and post-processing remain owned by the outer execution.
   After advancing, the handler MUST end its node with `TERMINATE`: returning
   `CONTINUE` would make the engine run every successor a second time and is
   rejected with `IllegalStateException`.

### Observers

1. **Hook order** per chain execution: `onChainStart` → per node
   [`onNodeStart` → `beforeNode` → `handler.handle(context, next)` →
   `afterNode` → `onNodeEnd`] → `onChainEnd`. Multiple observers run in registration
   (`@Order`) order for every hook.
2. **Scope tokens**: each `on*Start` returns a per-call token the engine
   pairs back to the same observer's `on*End` in a `finally` block (on
   handler exception `onNodeEnd` receives a `null` result — recover the
   token, do not fabricate decisions). Tokens carry per-call state; observers
   must be thread-safe and stateless between calls.
3. **Exception isolation**: observer hook failures are caught and logged by
   the engine; they never change chain control flow.

### Context

- `CacheContext` exposes a read-only `InputView` (operation, cacheName,
  redisKey, actualKey, valueBytes, deserializedValue, ttl, policy) plus the
  typed decisions (`TtlDecision`, `NullDecision`, `PrefetchDecision`,
  `keyPattern`) that named producer handlers write and `ActualCacheHandler`
  reads. Custom handlers SHOULD read only; writing decisions is reserved for
  the documented producer/consumer pairs.
- `markSkipRemaining()` is engine-only state materialized from `SKIP_ALL`;
  custom handlers must not call it.
- `byte[]` values cross the SPI by defensive copy (`CacheResult.resultBytes()`);
  do not mutate arrays handed to you and do not rely on retaining them.

### Nested public type classification

The machine gate (`public-surface-nested.txt` +
`PublicSurfaceContractTest`) pins this list. Classification:

| Nested public type | Class | Notes |
|---|---|---|
| `CacheResult.Outcome` / `FailureKind` | user | stable value semantics |
| `CacheContext.InputView` | user | read-only input view for handlers/tests |
| `CachePolicyView.Source` | implementation | adapter interface implemented by internal operation models; public only so internals can implement it — do not use from host code |
| `RedisProCacheProperties.*` (9 nested classes) | user | configuration binding surface |
| `CachingEnablementValidation.CachingEnabledValidator` | operator | health/startup probe |
| `RedisDeploymentValidator$RedisDeploymentChecks` | implementation | internal validation payload |
| `LockManager.LockHandle` | extension | stable lock handle (§1) |
| `SerializationException.EnvelopeCodec` | operator | envelope helpers for migration tooling |
| `SerializationMigrationCli$SerializationMigrationRunner`, `SerializationMigrationProperties$LegacySerializer` | operator | migration CLI surface |
| `cache.*$*` (Builder/Scope/Strategy/Outcome records etc.) | implementation | outer classes are package-private internals — not reachable from host code |

Removing or renaming an `extension`/`user` entry is a ⚠️ BREAKING change;
`implementation` entries may be internalized without a major bump.

## 5. 1.0 graduation (forward markers — pre-1.0)

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

When 1.0 ships, only the **caller-observable contracts** — §1 plus the §3
minimum (annotation attributes, `resi-cache.*` keys, wire envelope,
documented SPI behavior, typed failure semantics) — become **locked**;
changing one is a new major version. Items in §2 (internals, defaults,
metric names, log wording) stay minor-version evolvable in 1.x, flagged
with a ⚠️ BREAKING CHANGELOG entry when a caller-visible behavior changes.
Metric names lock only when a named metric/label contract with migration
notes is published in `COMPATIBILITY.md`.

## 6. How to read this document

- Pin to **exact `0.x.y`** versions if you depend on §2 behavior.
- Pin to **`0.x`** (minor-flexible) if you depend only on §1 + §3.
- At **`1.x`**, SemVer major bumps protect only the §1 + §3
  caller-observable contracts above. Metric names, log messages, and
  behavior defaults remain minor-version evolvable (§2) unless a bounded
  metric/label contract with migration notes is published.

---

## 7. References

- [`CHANGELOG.md`](./CHANGELOG.md) — per-version changelog including
  ⚠️ BREAKING markers.

> Accepted architecture decisions and their rationale live in the
> [`docs/adr/`](./docs/adr/README.md) index; Git history records ordinary
> implementation history and commit-level details.
