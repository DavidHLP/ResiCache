# Operations

ResiCache is a library, not a hosted service. This document covers the
operator-controlled runtime and release boundaries that are present in the
repository. It does not invent a deployment platform, backup promise, SLA, or
on-call policy.

## Runtime activation

1. The host application enables Spring Cache with `@EnableCaching`.
2. Spring Boot loads `RedisCacheAutoConfiguration` when Redis classes are
   present and `resi-cache.enabled` is not false.
3. `RedisProCacheProperties` binds and validates `resi-cache.*`.
4. The chain and its infrastructure are assembled once; protection-switch
   changes require an application restart.

The library does not enable Spring Cache for the host and does not scan host
application packages. The application's deployment mechanism owns process
lifecycle, secrets injection, and Redis availability.

## Redis topology and configuration

`spring.data.redis.*` configures the Spring Data Redis connection used for cache
I/O. `resi-cache.redis.*` configures the Redisson deployment used for locks and
synchronization; `resi-cache.redisson.*` controls its pool, timeout, and retry
settings. Configure matching topology explicitly in both namespaces when
`sync=true` is used. Redisson's single-node mode falls back to Spring's host
only for a blank library host, to Spring's password for a missing/empty library
password, and to Spring's database when the library database is `0`. Its port,
username and TLS flag use library properties; their Spring counterparts are not
copied. Defaults already supply localhost and port 6379, so setting only
`spring.data.redis.host` does not redirect the default Redisson connection.
Cluster and Sentinel use the library node/ACL configuration directly.

When Redisson is on the classpath, the library creates a client at startup
unless one already exists, even if no annotation uses synchronization. Omitting
Redisson is the minimal no-lock path; adding it requires a reachable deployment.

Supported deployment modes are `single`, `cluster`, and `sentinel`, with
binding-time validation for mode-specific fields and TLS requirements. The
advanced `resi-cache.redis.redisson-config-path` value is a trusted operator
input only: it is read as a local YAML path and must never come from an
end-user request. This override returns the file configuration directly; the
normal library topology/pool settings are not then applied.

Redisson is optional until an operation requests distributed synchronization.
With no distributed `LockManager`, `sync=true` fails closed by default.
`resi-cache.sync-lock.local-only=true` is an explicit single-JVM degradation
and must not be treated as multi-instance protection.

## Observability and diagnosis

Cache metrics are opt-in: they require both `resi-cache.metrics.enabled=true`
and the application's `MeterRegistry`, a decision resolved once during
assembly. When either is missing the metrics seam is a no-op adapter and
nothing is published. The Redis health indicator is not gated by that switch;
it needs the optional Actuator dependency and reports Redis connectivity plus
the sync-protection state: `protection.degraded=local-only` when no distributed
lock backend is present and `resi-cache.sync-lock.local-only=true` was
explicitly enabled, `protection.degraded=fail-fast` when no backend is present
without that opt-in, and no protection detail when a backend exists. The
indicator's overall status tracks Redis connectivity only: it stays UP while a
protection detail is attached.

Because that indicator is assembled whenever Actuator, Redis and ResiCache are
all present, **every `/actuator/health` probe costs one synchronous
`connection.ping()` Redis round trip**. An orchestrator or load balancer that
polls health frequently (a Kubernetes liveness/readiness probe on a short
period, for example) therefore adds that traffic to Redis for each probe, per
application instance. Previously the indicator was gated on
`resi-cache.metrics.enabled`, so applications that left metrics off had no
probe traffic at all. Size the health-check interval and any Redis connection
pool accordingly, and prefer a dedicated low-frequency probe over reusing the
health endpoint as a load-balancer check.

Writer statistics and failure reporting are bounded by
the contracts in `STABILITY.md` and `COMPATIBILITY.md`; pre-1.0 metric names
and log wording are not a general compatibility promise.

WARN/ERROR diagnostics omit raw cache keys. The source uses cache-name or a
short diagnostic fingerprint where available and keeps fuller detail at lower
log levels. Do not paste raw keys, credentials, Redis payloads, or full
production logs into repository documentation.

When triaging an incident, first identify the operation and configuration
source, then check binding failures, lock availability, serializer allowlists,
and Redis topology. Use the operation table in [`REFERENCE.md`](REFERENCE.md)
and the focused tests named in [`DEVELOPMENT.md`](DEVELOPMENT.md); do not infer
success from a process that merely started.

## Serialization rollout and rollback boundary

The `{version, payload}` envelope is not wire-compatible with Spring's generic
JSON or JDK serializers. A safe adoption flow is:

1. shadow-read through the new serializer;
2. dual-write the old and new representations for a bounded window;
3. confirm hit/error behavior and cut over;
4. retain a rollback path until the new representation is trusted.

The migration CLI and properties are operator-directed surfaces. They are not
run automatically at application startup. Run the full class name
`io.github.davidhlp.spring.cache.redis.serialization.migration.SerializationMigrationCli`
on a classpath containing the core JAR and its runtime dependencies. The plain
core JAR is not a self-contained executable. Configure
`spring.data.redis.*`, the serializer allowlist, and a bounded
`resi-cache.serializer.migration.pattern` before invoking it.

Require `resi-cache.serializer.fail-on-unknown-type=true` in the migration
CLI configuration. With `false`, unsupported envelope versions or ordinary
payload-binding failures can deserialize to `null`; the engine still counts
them as `envelopes` instead of `failed`, and the CLI can exit successfully.
Do not approve a keyspace based on such a permissive run; rerun validation
with fail-fast mode and verify the application's actual reads.

Before any write phase, reserve collision-free `shadow-suffix` and
`backup-suffix` namespaces under `resi-cache.serializer.migration`. Preflight
the complete intended source set: no application source key may end in either
suffix, and each derived `<source><suffix>` destination must be absent or
verified as a sidecar belonging to this migration. Stop on unrelated or
unverifiable destinations. The CLI does not enforce this reservation:
`writeSidecar` overwrites differing destination bytes with UPSERT, and forward
scans silently skip keys ending in either suffix. A dry run is not a collision
check. Prevent application writers from creating keys in the reserved
namespaces throughout migration and cleanup.

The CLI converts serializer bytes; it does not construct ResiCache's runtime
cache structure. Normal writes store a `CachedValue` wrapper containing the
chain value and expiry/refresh metadata, while `ActualCacheHandler` treats
values without that wrapper as cache misses. A directly serialized legacy
POJO or String can retain its concrete type after conversion and still be
unusable as a ResiCache cache entry.

For ResiCache keys, use the CLI only when the decoded legacy value already
has the complete compatible `CachedValue` structure, including its nested
value/envelope representation and metadata. Regenerate bare DTO/String values
or incompatible wrappers through normal application cache writes instead of
using `CUTOVER` to convert them. Use a strict allowlist limited to trusted
value packages and the required internal namespace. Field-level type metadata
does not require global default typing, and enabling that switch does not
supply the missing runtime wrapper.
Before `CUTOVER`, verify representative converted sidecars against the
application's actual cache read path, including wrapper structure, metadata,
nested values, concrete types and typed cache-hit behavior. Successful CLI
decoding or serializer round-tripping alone is insufficient.

| Phase | Effect |
|---|---|
| `SHADOW_READ` | Default; decodes and validates legacy values without writing. |
| `DUAL_WRITE` | Writes current-envelope sidecars with the source TTL; leaves legacy source bytes in place. |
| `CUTOVER` | Saves legacy backup sidecars, then compares/replaces unchanged source bytes with the current envelope, preserving TTL. |
| `ROLLBACK` | Uses backups; rejects differing existing source bytes but recreates missing sources from legacy backups. |

The CLI is an operator-directed conversion, not an application write interceptor.
Maintain concurrent application dual writes separately during the rollout.
`max-keys` limits the engine's `selected` count, not scanned keys or all
attempts. In forward phases, selection occurs only after legacy decoding and
serialization succeed; corrupt envelopes and decode/serialization failures
increment `failed` without consuming that limit. Failures after selection do
consume it. A run can therefore GET, decode and report every malformed
matching key even with a small `max-keys`. Valid current envelopes and already
completed entries also do not consume the limit. `batch-size` is only a SCAN
hint. There is no separate hard scan or attempt cap in the CLI.
Do not use `max-keys` alone as a production workload budget: restrict the
matched key population independently and apply an external execution deadline
when required. A match pattern does not bound Redis SCAN work, and externally
interrupted runs must be treated as incomplete.

Successful non-dry-run write phases can skip
completed entries while their stored state remains valid. `SHADOW_READ` and
`dry-run=true` persist neither completion state nor a SCAN cursor; repeating
an invocation with the same pattern and `max-keys` can select the same eligible
legacy keys again. To validate the whole keyspace, use disjoint bounded
patterns or a `max-keys` large enough to cover all eligible keys. Reaching the
limit does not establish that the remaining keys were validated.
`dry-run=true` prevents mutation. Forward-phase reports expose decoded legacy
counts, but rollback dry runs do not expose the private selected/planned
counter: `written` remains zero, and `scanned` includes pending restorations
and already-restored/no-op backups. They cannot establish how many keys would
be restored. Independently compare the scoped backups and source state before
authorizing rollback. Reported rejected/failed keys make the CLI exit
unsuccessfully; this requires the fail-fast validation configuration above.

Rollback does not protect deletions: if a source is missing while its backup
remains, it uses SET_IF_ABSENT to recreate the legacy value, which may resurrect
a deliberately evicted, stale entry. Quiesce application writes and evictions
for the affected keys before rollback, and keep them paused until it completes.
Quiescence alone cannot identify earlier intentional deletions: reconcile
those against the backup inventory and exclude their backups from rollback
before running it. Existing changed source bytes are rejected; absence is not
treated as evidence of an intentional deletion.

Sidecars inherit the source TTL at creation. A persistent source produces
persistent shadows/backups; neither cutover nor rollback deletes them, and the
CLI has no cleanup phase. Budget for their storage until explicitly removed.
After validated cutover and the agreed rollback window, or after a completed
rollback whose result has been verified, stop migration invocations and retire
application dual writes to these sidecars. Inventory the reserved namespaces,
review the exact sidecar keys belonging to this migration, then manually
remove only that approved set in controlled batches. Preserve backups while
rollback is still required; do not use a broad suffix-only deletion that could
include unrelated keys. Backups for expiring sources may expire before
rollback; backups for persistent sources have no automatic retention bound.
Sidecars are not a durable backup service.

A serializer change without this workflow can make existing values unreadable; a cache flush is not the only
rollback strategy and is not required by the documented migration flow.

## Release and publication boundary

The root POM and benchmark artifact are independently versioned; the benchmark's
`resicache.version` tracks the core. Update the core version, benchmark reference
and versioned Changelog entry through a PR before pushing `vX.Y.Z[-prerelease]`.
Tag and POM must match, the tagged commit must belong to main history, and Maven
Central coordinates must be unused. Numeric version/prerelease identifiers cannot
have leading zeroes; build metadata (`+...`) is not part of this project's tag
format. Any prerelease suffix produces a prerelease GitHub Release. The current
`0.0.2` coordinates already identify the old Boot 3 / Java 17 artifact and cannot
be reused for this build line.

The release workflow runs the shared full verification, packaged consumers and
benchmark smoke, then signs the exact verified candidate bytes in an isolated
GPG home. It uploads a Maven-layout bundle through the Central Portal Publisher
API with automatic publication and polls for at most 30 minutes. Only PUBLISHED
permits GitHub Release creation; validation, timeout or unknown states fail.
Public repository bytes must also match the candidate checksums before Release
creation. The Release includes artifacts, signatures, checksums, the commit/run manifest
and Central publication record. An upload timeout is not automatically retried:
the server may already have accepted it.

The `maven-central` environment is restricted to version tags. Configure
`CENTRAL_USERNAME` / `CENTRAL_PASSWORD` using a Portal user token,
`GPG_PRIVATE_KEY` / `GPG_PASSPHRASE` as secrets and `GPG_FINGERPRINT` as an
environment variable. Credentials and signing-key material never enter candidate
or publication artifacts. Old OSSRH credentials must not be assumed valid.
Local Maven publication uses the explicit `release` profile: signing binds to
verify, and the Central plugin automatically publishes and waits for completion.
Ordinary builds do not load release/signing plugins.

To recover a failed publication/Release run, dispatch `release.yml` **on the
original tag ref**, with `candidate-run-id` pointing to the original tag run
and its saved `deployment-id`. The original Verification gate must have passed;
the candidate commit, version, run and publication record must match. Recovery
never uploads again: it polls the original deployment and creates/completes the
GitHub Release only after PUBLISHED. Download the `release-publication` artifact
for `publication.json`; it is retained even on failure for 90 days, while the
raw candidate is retained for 30 days. Preserve both externally if longer
recovery is needed. If an upload response was lost before an ID was recorded,
locate the uniquely named deployment in the Portal; do not blindly rerun upload.

After the shared workflow passes, configure main to require PRs and the GitHub
Actions `ci-ok` check against an up-to-date branch, and disallow force pushes/deletion.
The single-maintainer configuration
does not require another person's approval. Secret scanning and push protection
remain enabled; Dependabot alerts and security updates complement CI scans.
Workflow files describe intended checks; remote branch/environment settings
must also be verified when configuring the repository.

Current publication status and its verification evidence are owned by
[`COMPATIBILITY.md`](../COMPATIBILITY.md). A local install, candidate bundle or
green CI run is not a publication claim. Public publication is triggered
separately by a maintainer's new version tag after configuring
namespace/signing access.

## Backup, restore, and hosted-service limits

The repository does not ship Redis backup/restore automation, a deployment
controller, a managed Redis service, or a production incident-response SLA.
Those responsibilities belong to the host application's platform and Redis
operator. Record and verify those external procedures in the deployment system
rather than adding a repository document that pretends they are implemented.

## Security boundary

Follow [`SECURITY.md`](../SECURITY.md) for private vulnerability reports. Keep
serializer allowlists, Redisson file paths, credentials, and deployment
configuration in trusted application/operator channels. The library's secure
serialization defaults and known constraints are part of the runtime contract;
changing them requires source, test, and compatibility review.
