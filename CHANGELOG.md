# Changelog

All notable changes to ResiCache are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html) **with the
pre-1.0 caveat below**.

## Pre-1.0 versioning policy

While the version is `0.x`, **APIs may change in any release — including patch
releases.** Breaking changes are marked with ⚠️ and explained in the release
notes. API stability is only guaranteed from `1.0.0` onward (see the
[Roadmap](README.md#roadmap) in README).

> **Note on the `v1.0` git tag.** A `v1.0` tag exists from an early development
> milestone ("defect remediation complete"). It **does not** denote an API-stable
> or publicly released version — the actual artifact version has always been
> `0.0.x`, and the public 1.0 release is tracked in the Roadmap. The tag is kept
> for history but carries no stability promise.

## [Unreleased] — v0.1.0 (in development)

### Changed
- ⚠️ **BREAKING (safety)** `SyncSupport` no longer **silently degrades** to a
  single-JVM `synchronized` monitor when `sync=true` is declared but no
  distributed lock backend is present (Redisson absent → no `LockManager`
  bean). It now **fails fast** with a clear `IllegalStateException` on the
  first cache miss — single-JVM coordination is useless across instances, and
  "distributed-but-actually-local" is the worst failure mode. A startup
  warning is also logged when the empty-backend condition is detected.
  **Migration:** for explicit single-instance/test degradation, set
  `resi-cache.sync-lock.local-only=true` (emits a
  `protection.degraded=local-only` warning; Observation event lands in v0.2.0).

### Added
- ⚙️ **WS-1.1 FIRE — Spring Boot 4.0 build line (dual-branch)**: ResiCache now
  builds and fully verifies (`verify -Pboot4`, 672 tests + JaCoCo 70%/40% gate)
  on **Boot 4.0 / SDR 4.0 / Spring 7 / Java 21 / Redisson 3.50** on the dedicated
  `boot4` branch, coexisting with the Boot 3.4.x `master` line. SDR 4 / Boot 4
  adaptations: `RedisCacheWriter.remove`→`evict` & `clean`→`clear` (delegating
  impls), `RedisCacheConfiguration.getTtl()`→`getTtlFunction().getTimeToLive(...)`,
  `RedisCacheManager` constructor arg reorder, Boot 4 package relocations
  (`DataRedisAutoConfiguration` / `DataRedisProperties` / `health.contributor`),
  `redisson-spring-boot-starter`→`redisson` core (the starter's auto-config
  hard-references Boot 3 classes), and a defensive `supportsAsyncRetrieve()=false`
  shim (Path C Step 6 will restore it). See [COMPATIBILITY.md](COMPATIBILITY.md)
  dual matrix and `.github/workflows/ci-boot4.yml`.
- `resi-cache.sync-lock.local-only` (default `false`) — explicit opt-in for
  single-JVM sync degradation when no distributed lock backend is available.
- `resi-cache.bloom-filter.rebuild-window-seconds` (default `30`; `0` = disabled) —
  after `@CacheEvict(allEntries=true)` (CLEAN) wipes the Bloom filter, opens a
  per-cacheName **rebuilding window** during which `mightContain` **fails open**
  (returns true), routing requests to the loader instead of silently returning
  null. See the WS-1.2c Fixed entry below.

### Fixed
- **Bloom filter CLEAR rebuilding window (WS-1.2c)**: previously, when
  `@CacheEvict(allEntries=true)` cleared the Bloom filter alongside the cache, the
  empty filter made every subsequent `mightContain` return false, and
  `RedisProCache.get(key, loader)`'s pre-check **silently returned null without
  invoking the loader** — violating the `@Cacheable` contract (a miss should call
  the loader and return the real value). This is a data-correctness defect, not a
  DB breakdown (the loader is never called); it only affects methods with
  `useBloomFilter=true`. `BloomSupport.clear` now opens a per-cacheName rebuilding
  window (Redis-backed flag, TTL = `rebuild-window-seconds`, Cluster-consistent via
  Redis + a 1s local cache) during which `mightContain` fails open, routing
  requests through the normal sync-lock + loader path; the window self-closes via
  Redis TTL. Set `rebuild-window-seconds=0` to restore v0.0.x behavior. Both the
  `RedisProCache.get(key, loader)` path and the chain path
  (`BloomFilterHandler.handleGet`) share `bloomSupport.mightContain`, so both are
  covered by this single-point fix.
- **Redis Cluster distributed lock hash-tag pinning (WS-1.2b)**: in Cluster mode,
  `DistributedLockManager` now ensures the lock key lands in the same slot as the
  cache key (via Redis hash-tag `{...}`), so the lock and the data it guards live
  on the same node and future in-lock MULTI/transactions won't hit cross-slot
  errors. `single`/`sentinel` modes are unchanged; lock-key format is also
  unchanged when the cache key already carries a hash-tag.

## [Unreleased] — planned for v0.0.3

### Added
- `resi-cache.enabled` master kill-switch — the auto-configuration is now gated
  by `@ConditionalOnProperty(prefix = "resi-cache", name = "enabled",
  matchIfMissing = true)`. Set `resi-cache.enabled=false` to fully disable
  ResiCache without removing the dependency.
- **Wildcard `.*` suffix support in `resi-cache.serializer.allowed-package-prefixes`**:
  `WhitelistPolicy.isClassNameAllowed` now matches a trailing `.*` on each prefix
  as a wildcard sentinel (in addition to the existing literal-prefix `startsWith`
  match). `com.example.*` now matches `com.example.Foo`, `com.example.sub.Bar`,
  `com.example.foo.bar.baz.Qux`, etc. (any depth) via the new private helper
  `matchesPrefix(className, prefix)`. Non-wildcard forms keep identical literal
  behavior — `STABILITY.md` §1 (resi-cache.* keys stable) holds, no key added
  or removed. Behavior-class: **additive only** — existing configurations
  behave identically; users adopting `.*` gain capability without affecting
  anyone. Round 9 TDD: 4 new tests in `WhitelistPolicyTest` (3 wildcard + 1
  backward-compat); full verify 673/0/0/0 ✅ (+4 vs Round 5 baseline 669).
  Red-phase correction recorded in the backward-compat test body comment:
  literal `com.example` still matches `com.exampleX.Foo` per `String.startsWith`
  semantics (no dot-boundary enforcement for literal form), since introducing
  it would break existing users — intentionally deferred as a separate decision.
- **`SecureJacksonSerializerFactory` (@Component) extracted** — Round 5 fix had
  to mirror the same 5-arg `SecureJacksonRedisSerializer` constructor across
  three bean assembly sites (`RedisConnectionConfiguration#redisCacheTemplate`,
  `RedisProCacheConfiguration#defaultRedisCacheConfiguration`,
  `TestRedisConfiguration#redisCacheTemplate`). Three sites holding identical
  wiring is a maintenance trap: any ctor-signature edit silently breaks one
  and re-introduces the wired/unwired two-track bug Round 5 just fixed. This
  commit extracts the wiring into a single `@Component` with one
  `create(ObjectMapper, SerializerProperties)` method; both production configs
  inject it. Test twin (`TestRedisConfiguration`) is left as-is since it's a
  `@TestConfiguration` outside the Spring component scan and would actually
  require MORE wiring to use the factory — net simpler to keep it explicit.
  New unit test `SecureJacksonSerializerFactoryTest`: 2 tests including the
  **negative-wiring guard** — explicitly setting
  `allowedPackagePrefixes=["com.example.round11"]` (no `io.github.davidhlp`)
  and roundtripping a `CachedValue` (whose `@class` resolves to
  `io.github.davidhlp...`) must throw `SerializationException` with "whitelist"
  in the message; if the factory silently used defaults instead of the
  configured props, the roundtrip would succeed. This is the regression guard
  for the entire Round 5 + R11 contract. Internal refactor — `STABILITY.md`
  §2 (internals may-change pre-1.0) applies; no public API surface touched.
  Full verify 675/0/0/0 ✅ (+2 vs Round 9 baseline 673). (loop round 11)
- **`CONTRIBUTING.md` requirements + new Maintainers & bus-factor section**:
  (a) Bumped JDK requirement `17+` → `21+` to match `pom.xml
  <java.version>21</java.version>` and the WS-1.1 FIRE cut (Round 8 caught the
  same drift in `.github/workflows/release.yml`; this is the `CONTRIBUTING.md`
  twin — pre-1.0 docs/harness drift between the source manifest and the
  human-facing contributor guide). (b) Added `## Maintainers & bus factor`
  section documenting the current single-maintainer structure honestly (bus
  factor 1) and the implications for `STABILITY.md` §4 graduation criterion #6
  (named successor OR documented succession plan required before 1.0 tag).
  Forward-link to STABILITY makes the graduation gate explicit. Docs-only —
  `STABILITY.md` §2 (docs may-change pre-1.0) applies; §1+§3 not invoked.
  (loop round 12)
- **Composite GitHub Action `.github/actions/setup-jdk-21`** — extracted 6
  `actions/setup-java@v5` calls across 3 workflows (`ci.yml` × 4,
  `pr-checks.yml` × 1, `release.yml` × 1) into a single composite action.
  Eliminates the 6-way drift surface for JDK version + distribution +
  Maven cache config that Round 8 partially fixed in `release.yml` only;
  the composite is now the single source of truth. Defaults pin `java-version:
  '21'` to match `pom.xml <java.version>`; workflow callers override only the
  release-specific `with:` keys (`server-id`, `server-username`,
  `server-password`, `gpg-private-key`, `gpg-passphrase`) — `ci.yml` and
  `pr-checks.yml` need no `with:` at all (defaults suffice). YAML syntax
  validated via PyYAML for all 4 touched files. Pure YAML refactor — no
  secrets, no triggers, no env values changed; `STABILITY.md` §2
  (internals may-change pre-1.0) applies; §1+§3 not invoked.
  (loop round 13)
- **`CONTRIBUTING.md` Releases & CI infrastructure subsection** — new
  guidance pointing future contributors at the composite action
  (`.github/actions/setup-jdk-21/action.yml`) as the JDK source of truth,
  with explicit "JDK bumps must edit only the composite" + "release.yml
  deploy secrets are configured OOB by the maintainer; do not edit
  release.yml to add secrets" guard rails. Helps prevent Round 8/R13-style
  drift if a new contributor comes in and re-adds an inline
  `actions/setup-java@v5` step unaware of the composite structure. The
  reference is the rounding-the-loop of Round 13's refactor: useful on
  its own only if a reader can find it. Docs-only — `STABILITY.md` §2
  (docs may-change pre-1.0) applies; §1+§3 not invoked.
  (loop round 14)
- **Startup-time misconfig WARN** — new `@Component
  SerializerWhitelistStartupGuard` listens to `ApplicationReadyEvent` and
  warns at WARN level when `resi-cache.serializer.allowed-package-prefixes`
  is `null` or `[]`. Without this guard, an over-eager user clearing the
  whitelist to "be permissive" gets silent runtime
  `SerializationException` on every custom-type deserialize — the most
  common misconfig footgun for SecureJackson. Predicate `shouldWarn()` is
  package-private for unit testability. **Non-breaking** — default value
  stays `[io.github.davidhlp]`, no property key added/removed, no wire
  format change. `STABILITY.md` §1+§3 not invoked; §2 (internals
  may-change pre-1.0) applies. Round 15 TDD: 4 tests in
  `SerializerWhitelistStartupGuardTest` (null / [] / [io.example.app] /
  default); full verify 679/0/0/0 ✅ (+4 vs Round 11 baseline 675). This
  is the **WARN scaffolding** for the larger GUIDE §4 "whitelist
  auto-derive" item (which would additionally BeanFactory-derive the host
  app root package — that part is ⚠️ BREAKING and intentionally deferred).
  (loop round 15)
- **`## Comparison` section added to `README.md` / `README.zh-CN.md`**
  promoting [`docs/comparison.md`](docs/comparison.md) to a discoverable
  README-visible surface (guide §6 line 174). Headline copy: "the 3
  protections JetCache is missing, in one Redisson-native chain" — bloom
  (penetration), TTL jitter (avalanche), distributed breakdown lock
  (breakdown). Cross-references [ADR-0006](wiki/adr/0006-redisson-companion-positioning.md)
  for the positioning rationale. The detail (feature matrix, honest
  trade-offs) stays in `docs/comparison.md`; README gets a one-paragraph
  pointer + headline so readers who land on the repo front door can
  decide whether to drill in. No public API change. STABILITY.md §2
  (docs may-change pre-1.0) applies; §1+§3 not invoked. Docs-only
  change, no `./mvnw clean verify` needed.
  (loop round 16)
- **`wiki/modules/serialization.md` sync to current source** (round 17)
  — wiki page had drifted significantly since its initial creation:
  - **`source-files` frontmatter listed `SecureJackson2JsonRedisSerializer.java`
    which was renamed to `SecureJacksonRedisSerializer.java`** — fixed
  - **Missing** `SecureJacksonSerializerFactory` (Round 11 extracted it to
    `@Component` to eliminate the wired/unwired two-track bug Round 5
    exposed) — added with assembly-flow paragraph explaining "don't
    `new` the serializer directly, use the factory"
  - **Missing** `WhitelistPolicy` (Round 9 added `.*` wildcard suffix
    support with dot-boundary protection via `matchesPrefix` helper) —
    added with explicit note that literal prefix intentionally lacks
    dot-boundary (Round 9 design choice, candidate 4 still deferred as
    BREAKING)
  - **Missing** `VersionEnvelope` (STABILITY §3 never-change wire
    format) and `SerializationException` (failure path) — added
  - **`failOnUnknownType` default corrected** — wiki said "false, 降级";
    actually `true` (Round 5 confirmed) and the "降级到 miss" is
    performed downstream by [[cache-lifecycle]] error handling, not
    silently inside the serializer
  - **Rejection-message remediation hint** (Round 3 added) — surfaced
    under `SecureJacksonRedisSerializer` section so the wiki now matches
    the user-facing string
  - **`updated:` frontmatter bumped to 2026-06-29**

  No public API change. STABILITY.md §1+§3 not invoked; §2 (docs
  may-change pre-1.0) applies. The wiki is the LLM-consulted
  knowledge base per `CLAUDE.md` — if it doesn't reflect the actual
  state of source, future LLM sessions re-derive from source (the
  very failure mode the wiki exists to prevent).
  (loop round 17)
- **`wiki/modules/configuration.md` sync to current source** (round 18)
  — continued Round 17's bounded wiki-sync pattern (1 page per round).
  Changes:
  - **`fail-on-unknown-type` default corrected** in serializer yaml
    example: was `false` ("降级"); actually `true` (Round 5
    confirmed) with "降级到 miss" performed downstream by
    [[cache-lifecycle]] error handling
  - **`polymorphic-typing-enabled` default corrected**: was `true`;
    actually `false` (Round 5 confirmed)
  - **`allowed-package-prefixes` `.*` wildcard** (Round 9) added
    inline note to serializer section
  - **New `## 启动期守卫 (SerializerWhitelistStartupGuard, R15)`**
    section — explains the @Component that fires WARN on
    `ApplicationReadyEvent` when whitelist is null/[], the property
    key it guards, and explicitly cross-references the larger
    guide §4 "whitelist auto-derive" item (⚠️ BREAKING) of which
    this is the WARN scaffolding
  - **`updated:` frontmatter bumped to 2026-06-29**
  - **Related-link list** updated to point at
    `SerializerWhitelistStartupGuard` assembly context under
    [[auto-configuration]]

  No public API change. STABILITY.md §1+§3 not invoked; §2 (docs
  may-change pre-1.0) applies.
  (loop round 18)
- **`wiki/modules/observability.md` sync to current source** (round 19)
  — continued Round 17/18 bounded wiki-sync pattern (1 page per
  round). R15 startup WARN is observability surface (loud-startup
  misconfig detection via log, not Micrometer), so it belongs
  here in addition to its primary home in [[configuration]] and
  [[serialization]]. Changes:
  - **`source-files` frontmatter** added
    `SerializerWhitelistStartupGuard.java`
  - **`related` list** added `serialization`
  - **`updated:` frontmatter** bumped to 2026-06-29
  - **Related-link list** — configuration row updated to mention
    SerializerWhitelistStartupGuard assembly context; new
    serialization row for the WARN trigger condition
  - **New `## 启动期 misconfig 告警 (loud-startup observability)`**
    section — frames startup WARN as a deliberate observability
    strategy ("把昂贵的 runtime 失败提前到零成本的 startup 日志
    检查"). Two startup guards documented:
    - `SerializerWhitelistStartupGuard` (R15) — guards
      serialization safety door
    - `SyncLockProperties.localOnly` (pre-existing) — guards
      distributed-lock consistency under multi-instance deployment
  - Explicit note that the two are **independent, not redundant
    or substitutable** — different misconfig defense surfaces

  No public API change. STABILITY.md §1+§3 not invoked; §2 (docs
  may-change pre-1.0) applies.
  (loop round 19)
- **`SerializerWhitelistStartupGuardIntegrationTest`** — new
  integration test closing the R15 test coverage gap (round 20).
  R15's `SerializerWhitelistStartupGuardTest` covered the
  `shouldWarn()` predicate but not the actual Spring event-firing
  path. This new test uses `ApplicationContextRunner` + Logback
  `ListAppender` to verify the end-to-end chain:
  `ApplicationReadyEvent` → `@EventListener` invocation →
  `shouldWarn()` evaluation → SLF4J WARN emission.
  Three scenarios: empty list (must warn, message contains
  remediation hint with both `com.example.*` and
  `com.example.dto`); populated list (no warn); default
  `[io.github.davidhlp]` (no warn). Implementation note:
  `ApplicationContextRunner` does NOT auto-fire
  `ApplicationReadyEvent` (that's `SpringApplication.run()`'s
  job), so the test manually publishes the event after context
  startup. The 4-arg ctor
  `(SpringApplication, String[], ConfigurableApplicationContext, Duration)`
  is the Spring Boot 4.0 signature — earlier 3-arg ctor was
  removed. **Design tradeoff**: beans registered via `withBean(...)`
  lambdas instead of `@Configuration @Bean` — the latter would be
  picked up by component scan in other IT test contexts and
  conflict with the production `@ConfigurationProperties` bean
  (verified by the verify-red event during R20: 13+ test classes
  failed "Parameter 2 of method redisCacheTemplate" with
  duplicate-bean errors before switching to `withBean`).
  No public API change. Full verify 682/0/0/0 ✅ (+3 vs
  R15 baseline 679). STABILITY.md §1+§3 not invoked;
  §2 (tests may evolve pre-1.0) applies.
  (loop round 20)
- **`wiki/architecture/auto-configuration.md` sync to current source**
  (round 21) — continued Round 17/18/19 bounded wiki-sync pattern
  (1 page per round; R20 broke to add an integration test, R21
  back to docs). R11 + R15 both live in the auto-configuration
  flow and this page is their primary home. Changes:
  - **`native-annotation-mode` default factually corrected**:
    wiki said `FULL` (default); actually `SELECTIVE` (R2 onward)
    — "避免双 Advisor"策略选择。`FULL` 是「旧项目零改动迁移」
    路径,`SELECTIVE` 是大多数 ResiCache 用户的默认。`NONE`
    是性能敏感场景的明确切到 ResiCache-only。
  - **`source-files` frontmatter** added
    `SecureJacksonSerializerFactory.java` (R11) +
    `SerializerWhitelistStartupGuard.java` (R15)
  - **`related` list** added `serialization` + `observability`
  - **`updated:` frontmatter** bumped to 2026-06-29
  - **New `## 序列化装配 (避免 wired/unwired 双轨 bug)` section** —
    documents `SecureJacksonSerializerFactory` as the single
    entry point for both `defaultRedisCacheConfiguration` and
    `RedisConnectionConfiguration#redisCacheTemplate` (R5 fix +
    R11 refactor); explicit warning "不要直接 `new SecureJacksonRedisSerializer(objectMapper)`"
  - **New `## 启动期守卫 (SerializerWhitelistStartupGuard, R15)`
    section** — explains the bypass-guard nature ("不在装配链上,
    是独立旁路"),points at integration test (R20) coverage,
    cross-references the other startup guard
    `SyncLockProperties.localOnly` for context
  - **Related-link list** — configuration row updated;
    serialization row added for factory assembly context;
    observability row added for the loud-startup observability
    strategy

  No public API change. STABILITY.md §1+§3 not invoked; §2 (docs
  may-change pre-1.0) applies. Pure docs.
  (loop round 21)
- **`sync-lock.timeout` wiki fact correction** (round 22) — closes
  a real stale fact noted in R18 as scope-limit. The
  `wiki/modules/configuration.md` sync-lock yaml example showed
  `timeout: 10s` but the actual code default is
  `SyncLockProperties.timeout = 3000` +
  `unit = TimeUnit.MILLISECONDS` = **3 seconds** (R5 recon
  verified). Fixed:
  - `timeout: 10s` → `timeout: 3000` (raw milliseconds, matches
    the `unit` field)
  - Added explicit `unit: MILLISECONDS` line to make the unit
    choice visible (java.util.concurrent.TimeUnit enum:
    NANOSECONDS / MICROSECONDS / MILLISECONDS / SECONDS)
  - Added a ⚠️ historical-discrepancy note: "要 10 秒须显式
    `timeout: 10000` 或 `timeout: 10 + unit: SECONDS` 两种写法任一"
  - This is the second fact-correction pass through
    `wiki/modules/configuration.md` (R18 did
    `fail-on-unknown-type` + `polymorphic-typing-enabled` defaults;
    R22 does `sync-lock.timeout` — both pre-existing wiki errors
    that no prior round caught)

  No public API change. STABILITY.md §1+§3 not invoked; §2 (docs
  may-change pre-1.0) applies. Pure docs.
  (loop round 22)
- **`wiki/architecture/cache-lifecycle.md` minimal touch-up** (round
  23) — substantive content already correct from R1 (the 5
  CacheOperation types, the `RedisProCacheWriter` entry point,
  the GET path with `PREFETCHED_CACHED_VALUE` reuse, the CLEAN
  SCAN+DEL constants, error handling — all match the source).
  R23 made three small additions rather than a full sync:
  - **`updated:` frontmatter** bumped to 2026-06-29
  - **`source-files` frontmatter** added
    `PostProcessHandler.java` (referenced in CLEAN-path
    paragraph; exists at
    `chain/PostProcessHandler.java` per `CacheHandlerChain`
    invocation verification)
  - **`related` list** added `configuration` (cross-link to
    startup-guard context)
  - **New opening paragraph** in `## 入口:RedisProCacheWriter`
    section — disambiguates **runtime lifecycle** (page
    focus) from **startup-time misconfig defense**
    (`SerializerWhitelistStartupGuard` R15 + `localOnly`
    warning; they fire on `ApplicationReadyEvent` BEFORE the
    first cache call, so they're not part of the per-call
    lifecycle but a precondition for it)
  - **`[[cache-avalanche]]` location disambiguation note** —
    the page referenced `[[cache-avalanche]]` without
    clarifying it lives in `wiki/concepts/` (concept
    category), NOT `wiki/mechanisms/` (mechanism category
    where [[bloom-filter]] / [[breakdown-lock]] /
    [[early-expiration]] / [[null-value]] / [[ttl-jitter]]
    live). Obsidian wiki link resolution works by filename
    regardless of directory, so the link is functional, but
    the categorization difference is real
  - **Related-link list** added `configuration` row for
    startup-guard context

  No public API change. STABILITY.md §1+§3 not invoked; §2
  (docs may-change pre-1.0) applies. Pure docs.
  (loop round 23)
- `resi-cache.protection.enabled` protection-chain switch — when `false`, the
  protection handlers (bloom/lock/early-expiration/null-value) are skipped but
  **TTL is preserved** (TtlHandler also computes the base TTL; disabling it would
  cause permanent caching). Startup-only (chain is cached as a singleton).
- Reactive return-type detection now logs an explicit **"caching will NOT take
  effect"** warning instead of implying a graceful fallback.
- `STABILITY.md`: API stability contract for the pre-1.0 0.x line. Lays out
  what is stable across all 0.x releases (annotations, `resi-cache.*`
  property keys, `{version,payload}` wire format), what may change
  (internals, defaults, package layout, pre-1.0 metric namespace), what
  never changes without a major bump, and the seven 1.0 graduation
  criteria. Required reading before any public API surface change per the
  loop chicken-egg gate.
- **Per-handler chain observability — correlated DEBUG + MDC requestId
  (foundation)**: `CacheHandlerChain.execute` now stamps a per-execution
  `requestId` into the SLF4J `MDC` (`CacheHandlerChain.MDC_REQUEST_ID_KEY`),
  and `AbstractCacheHandler` emits a single
  `[chain] handler={} decision={} key={} requestId={}` DEBUG line at the
  chain-advance point for every handler the engine evaluates. One GET/PUT's
  DEBUG trace is now correlated by a single `requestId` across all handlers
  and their decisions (guide §223d / line 388 / line 248 contract).
  Defensive snapshot/restore — only its own MDC key is touched (never
  `MDC.clear()`, so host MDC like `traceId` is preserved); `requestId` uses
  `ThreadLocalRandom` (non-blocking on the cache hot path, avoiding
  `SecureRandom` entropy contention). No public API surface touched (internal
  logging + MDC only; `STABILITY.md` §2 internals may-change). Foundation of
  the guide §223 per-handler observability item — Observation spans, per-handler
  `resicache.handler.<name>.fired` counters, and Micrometer tags land in
  subsequent rounds. TDD: 2 new tests in `CacheHandlerChainTest`
  (`MdcObservabilityTests`: requestId correlates across handlers + MDC cleared
  post-execution + caller MDC restored); full verify 684/0/0/0 ✅ (+2, Skipped:
  0, Testcontainers IT executed). (loop round 24)

### Changed
- ⚠️ **BREAKING** `nativeAnnotationMode` default changed from `FULL` →
  **`SELECTIVE`**: plain `@Cacheable` / `@CachePut` / `@CacheEvict` methods are
  now handled entirely by Spring's native cache infrastructure (no longer
  intercepted by ResiCache), which removes the dual-advisor risk. **Migration:**
  if you relied on ResiCache intercepting `@Cacheable`, set
  `resi-cache.native-annotation-mode=FULL` explicitly. Use `@RedisCacheable`
  for protection.
- Whitelist rejection message now includes the remediation property key:
  `resi-cache.serializer.allowed-package-prefixes`. Previously the message
  read "Type not in deserialization whitelist: <class>", leaving users to
  round-trip the docs to discover the property name. Per the
  `COMPETITIVENESS_GUIDE.md` §3 pillar B1 first-contact repair — the most
  common first-key failure no longer requires a doc round-trip.
  Diagnostic message text is classified pre-1.0 "may change" per
  `STABILITY.md` §2 — no public API surface impact.

### Fixed
- Redisson is now a *true* optional dependency: the `RedissonClient` bean and
  all Redisson-specific configuration have been isolated into a dedicated
  configuration class with a class-level `@ConditionalOnClass(RedissonClient.class)`,
  so booting without Redisson on the classpath no longer risks a
  `NoClassDefFoundError`. (`DistributedLockManager` was already class-guarded;
  `SyncSupport` degrades to a JVM-internal monitor when no lock manager is
  present.)
- **`resi-cache.serializer.*` properties were silently dropped by the low-level
  `RedisTemplate<String, Object> redisCacheTemplate` bean** (the one used by
  `RedisProCacheWriter` for direct `opsForValue/HashOperations`). Setting e.g.
  `resi-cache.serializer.allowed-package-prefixes=com.myapp.domain` or
  `resi-cache.serializer.polymorphic-typing-enabled=true` was honored by the
  `@Cacheable` path (production `RedisProCacheConfiguration
  #defaultRedisCacheConfiguration`, lines 63-69) but **not** by the writer
  path → two code paths, two different whitelists, silent inconsistency. The
  bug had been latent since v0.0.1: the bean instantiated
  `new SecureJacksonRedisSerializer(objectMapper)` (no-list ctor = defaults)
  instead of mirroring the 5-arg ctor that takes property values. Fixed by
  injecting `RedisProCacheProperties` and passing `getSerializer()
  .{getAllowedPackagePrefixes, isFailOnUnknownType, getTypeProperty,
  isPolymorphicTypingEnabled}` — same wiring shape as
  `RedisProCacheConfiguration`. Test mirror `TestRedisConfiguration
  #redisCacheTemplate` (`@Primary` IT bean) fixed symmetrically so ITs
  actually verify the production bean. `STABILITY.md` §1 lists
  `resi-cache.*` keys as stable, which implies behavior consistency; this
  fix tightens the contract. Regression test:
  `RedisConnectionConfigurationTest` (Testcontainers, `@DynamicPropertySource`
  sets custom whitelist + polymorphic on, roundtrips a POJO from
  `com.example.round5`, asserts deserialized is the original POJO instance —
  would fail before this fix with "Type not in deserialization whitelist" or
  degrade to `LinkedHashMap` if whitelist alone were honored). TDD: failing
  test → fix → green, full verify 669 tests / 0 failures / 0 skipped.
  (loop round 5)
- **`.github/workflows/release.yml` `JAVA_VERSION` 滞后:** 该工作流曾是
  Java 17 时遗留,`env.JAVA_VERSION: '17'` 没在 WS-1.1 FIRE(commit
  `38c514a`,unify 全栈 Java 21)时被同步,导致 release tag push 时
  `setup-java` 安装 JDK 17,而 `pom.xml` `<java.version>21</java.version>` +
  `maven.compiler.{source,target}=21` 要求 JDK 21 编译,Java 21 语法
  (records / sealed types / pattern matching / switch expressions)在
  JDK 17 编译必失败。`ci.yml` 同 WS-1.1 FIRE 期间已同步到 `'21'`,
  `release.yml` 是遗漏(loop round 8 静态 lint 扫描发现)。修复:`17`→
  `21` + 注释引 WS-1.1 FIRE commit + 解释为何必须对齐 pom.xml。**Only
 改动 `env.JAVA_VERSION` 一行 + 注释**,不动 secret / tag 触发器 /
  `softprops/action-gh-release` 步骤 / 任何 workflow 触发条件(loop §1
  「no outward-facing/irreversible actions: no push / no deploy / no gh
  actions / no tags / no merges」)。本地 commit,不 push;下一次 tag push
  才会真触发该 workflow。STABILITY 不涉及(workflow 配置非 public API
  surface)。

### Documentation alignment
- Reconcile `CLAUDE.md` and `AGENTS.md` to current versions: Java 21 (was
  "17+"), Spring Boot 4.0.0 (was 3.4.13), Redisson 3.50.0 (was 3.27.0).
  `AGENTS.md` was reduced to a pointer to `CLAUDE.md` because its prior
  full-duplicate had drifted — it still listed the `a5ab55b`-removed
  `wrapper/`/`spi/`/`event/`/`evaluator/`/`CacheMetricsRecorder` and described
  customizability via "Java ServiceLoader" (superseded by Spring
  `@Bean` + `@ConditionalOnMissingBean`). Pointer preserves tool compatibility
  while preventing future drift. (loop round 1)
- **ADR-0006 JetCache 覆盖机制算术修正(事实错误修复)**:原文 Context
  §第 1 段把 TTL jitter 计入 JetCache 覆盖(称"4/5"),与 JetCache 实际能力
  不符。**JetCache Issue #269**(TTL jitter / expiry randomization)状态为
  **closed unimplemented**——维护者判定不予实现。修正为:**3/5 覆盖**
  (`@CacheRefresh`≈EarlyExpiration、`@CachePenetrationProtect`≈SyncLock、
  null-value),并增加 **Amendment 2026-06-29** 段说明修正动机、来源
  (`COMPETITIVENESS_GUIDE.md` §3 战略执行条目)与版本对齐(`STABILITY.md`
  §1+§3 不涉及)。ResiCache 真实技术增量相应更正为 **Bloom + TTL jitter +
  可插拔责任链**(3 项独立、不重叠)。诊断/事实文本按 `STABILITY.md` §2
  属 pre-1.0 may-change,无 public API surface breaking。(loop round 4)
- **`docs/comparison.md` 与 ADR-0006 amendment 一致性补齐**:Round 4 修了
  ADR-0006 第 1 段的 JetCache 覆盖算术,但用户面对外的 `docs/comparison.md`
  能力矩阵防雪崩(TTL 抖动)行第 4 列**仍写「JetCache ✅」** —— 与 ADR-0006
  amendment + JetCache Issue #269 真实状态(JetCache 不实现 TTL jitter)矛盾。
  改 JetCache 列单元格为 ❌ 并加脚注引用,后置脚注段
  (`## 脚注`,锚点 `fn-jetcache-269`)说明 JetCache 实际覆盖数为 **3/5**,指向
  ADR-0006 Amendment 2026-06-29。**重要性**:`docs/comparison.md` 是
  `STABILITY.md` §4.7 1.0 毕业条件 #7 的 adoption 信号页(外部 referrer 月访问),
  行级事实错误直接误导读者在 JetCache vs ResiCache 之间做错决策。诊断/事实
  文本 pre-1.0 may-change 按 `STABILITY.md` §2,无 public API breaking。
  (loop round 7)

## [0.0.2] — current

### Removed — over-engineering cleanup (commit `a5ab55b`, ~2,989 lines)
⚠️ The following were removed as dead/over-engineered code. They had been
documented in README/CLAUDE.md but never shipped as stable features:

- `wrapper/` — `CircuitBreakerCacheWrapper`, `RateLimiterCacheWrapper`
  (circuit breaking / rate limiting) → use [Resilience4j](https://resilience4j.readthedocs.io/)
- `spi/` package (Java ServiceLoader provider model) — `BloomFilterProvider`,
  `LockProvider`, `RedissonLockProvider`, plus SPI-domain `BloomFilter`,
  `LockHandle`, `LockManager`. Note: only the ServiceLoader SPI package was
  removed — same-named interfaces live on under `protection/breakdown/LockManager`
  and `protection/bloom/filter/BloomIFilter`; customizability is via Spring beans.
- `event/` — `CacheEvictedEvent`
- `evaluator/` — `SpelConditionEvaluator`
- `observability/CacheMetricsRecorder` (only `RedisCacheHealthIndicator` remains)

Customizability is preserved through Spring beans: the strategy implementations
(`BloomIFilter`, `LockManager`) are plain `@Component`s, overridable via your own
`@Bean` + `@ConditionalOnMissingBean` — no Java ServiceLoader.

### Added
- Five protection mechanisms via a composable responsibility chain
  (ordering is a single source of truth in `HandlerOrder`, gap = 100):
  - Bloom filter (100) — penetration
  - Distributed lock (200) — breakdown
  - Early expiration (250) — hot key
  - TTL jitter (300) — avalanche
  - Null value (400) — penetration
- Enhancement annotations: `@RedisCacheable`, `@RedisCachePut`,
  `@RedisCacheEvict`, `@RedisCaching`.
- `SecureJackson` whitelisted deserialization (defense against Jackson
  polymorphic-type attacks) with a `{version, payload}` envelope.
- `RedisCacheHealthIndicator` (actuator health).

### Documentation alignment
README and CLAUDE.md still referenced the removed modules at the time; the LLM
wiki (`wiki/log.md`) records this discrepancy and treats source as the single
source of truth. The README rewrite in this cycle (see Unreleased) closes that
gap.

## [0.0.1] — initial (unpublished)
Initial development. No separate changelog was maintained; superseded by the
0.0.2 cleanup. The artifact version has been `0.0.x` throughout development.

---

Link reference policy: this file links to the README and wiki within the repo.
External links point to upstream tooling (Resilience4j, Caffeine, Keep a
Changelog, SemVer).
