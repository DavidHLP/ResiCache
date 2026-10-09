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
`sync=true` is used.

Supported deployment modes are `single`, `cluster`, and `sentinel`, with
binding-time validation for mode-specific fields and TLS requirements. The
advanced `resi-cache.redis.redisson-config-path` value is a trusted operator
input only: it is read as a local YAML path and must never come from an
end-user request.

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
run automatically at application startup. `DUAL_WRITE` is a one-time batch
conversion to shadow sidecars, not interception of subsequent application
writes. Provide actual application dual writes separately, or quiesce writers
and evictions for the affected keys through validation and cutover. CAS rejects
source changes during cutover; it does not establish a continuous-write protocol.

### Prepare and invoke the operator

Use JDK 21 and the checked-out core JAR with its production runtime dependencies.
The plain library JAR has no executable launcher; do not use `java -jar`.
From the repository root, reuse the existing independent consumer POM template:

```bash
(
set -euo pipefail
./mvnw install -DskipTests -B
operator_dir="$(mktemp -d)"
trap 'rm -rf "$operator_dir"' EXIT
boot_version="$(./mvnw help:evaluate -Dexpression=project.parent.version -q -DforceStdout)"
core_version="$(./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout)"
sed "s/@BOOT_VERSION@/$boot_version/g" scripts/ci/consumer/pom.xml > "$operator_dir/pom.xml"
./mvnw -f "$operator_dir/pom.xml" -Pminimal -Dresicache.version="$core_version" \
  dependency:build-classpath -Dmdep.includeScope=runtime \
  -Dmdep.outputFile="$operator_dir/classpath" -B
java -cp "$(cat "$operator_dir/classpath")${APP_VALUE_CLASSPATH:+:$APP_VALUE_CLASSPATH}" \
  io.github.davidhlp.spring.cache.redis.serialization.migration.SerializationMigrationCli \
  --spring.config.additional-location=file:/secure/resicache-migration.properties \
  --resi-cache.serializer.migration.pattern='myapp:orders:*' \
  --resi-cache.serializer.migration.phase=SHADOW_READ \
  --resi-cache.serializer.migration.dry-run=true
)
```

Run these build commands in the project development environment; run the Java
invocation only in the approved operator environment. Replace the example
pattern and trusted configuration path. Set `APP_VALUE_CLASSPATH` to the JARs
containing the actual application value classes and their dependencies when
needed; the minimal consumer profile supplies library dependencies, not host
classes. Do not put a host Boot executable JAR's nested libraries directly on
this classpath. The subshell stops on failure and cleans up its temporary files.

In the protected configuration file or the deployment's existing secrets
injection, configure `spring.data.redis.*` for the intended Redis deployment,
`resi-cache.serializer.allowed-package-prefixes` for only the trusted value
packages (and required internal types), and the actual legacy format through
`resi-cache.serializer.migration.legacy-serializer`. Keep passwords and payloads
out of command arguments and public logs. Set
`resi-cache.serializer.fail-on-unknown-type=true`: permissive decoding can return
null for invalid current envelopes, which the engine can count as `envelopes`
rather than `failed`. Successful CLI validation alone does not establish a
usable cache hit: test representative converted values through the application's
actual read path, including the compatible `CachedValue` wrapper, nested values
and expiry metadata. Bare legacy DTO/String values are not automatically given
that runtime structure; regenerate incompatible entries through application
cache writes instead of cutting them over.

### Write preflight and recovery

Before any write phase, inventory the complete intended source set and reserve
collision-free `shadow-suffix` and `backup-suffix` namespaces. No source key may
end in either suffix; every derived destination must be absent or verified as
belonging to this migration. Stop on unrelated or unverifiable destinations.
The CLI skips source keys ending in these suffixes and uses UPSERT to overwrite
differing sidecar bytes; a dry run does not detect namespace conflicts. Prevent
other writers from creating reserved keys throughout the migration.

Always specify pattern, phase and dry-run explicitly. The default phase is
read-only `SHADOW_READ`, but `dry-run` itself defaults to false. After a successful
preflight, run `DUAL_WRITE` with dry-run false to produce representative sidecars,
verify actual application reads, then authorize `CUTOVER` separately. Reuse the
same source pattern and suffix settings for `ROLLBACK`; the engine appends the
backup suffix itself. Phase and budget semantics are in
[`REFERENCE.md`](REFERENCE.md#migration-phase-and-budget-semantics).

Inspect the final summary (`scanned`, `envelopes`, `decodedLegacy`, `written`,
`skippedSidecars`, `failed`) and process exit status. Rejected/failed keys make
the CLI exit nonzero, but earlier writes can already have succeeded. Resolve
the cause and reconcile source/sidecar state before retrying; an interrupted
run is incomplete. There is no persisted SCAN cursor or durable checkpoint,
and repeating a dry run or SHADOW_READ can validate the same subset again.
`max-keys` is not a scan, attempt or elapsed-time budget. Even a narrow MATCH
pattern does not bound Redis SCAN work; independently scope the key population
and apply an external execution deadline when required.

Rollback rejects changed existing source bytes, but recreates missing sources
from backups with SET_IF_ABSENT. This can resurrect intentionally evicted stale
values. Quiesce writes and evictions, reconcile prior deletions against the
backup inventory, and exclude their backups before authorizing rollback.
Backups may already have expired; rollback is not guaranteed for every key.

Sidecars copy the source's remaining TTL at creation; persistent sources produce
persistent sidecars. Neither cutover nor rollback removes them and there is no
cleanup phase. Retain backups for the agreed rollback window; after validating
the result and retiring migration/dual-write activity, review and manually
remove only the inventoried sidecars belonging to this migration in controlled
batches. Sidecars are not a durable backup service.

A serializer change without this workflow can make existing values unreadable;
a cache flush is not the only rollback strategy and is not required by the
documented migration flow.

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

The Boot 4 / Java 21 line remains source-first until a matching Central artifact
is actually published and verified. A local install, candidate bundle or green
CI run is not a publication claim. Public publication is triggered separately
by a maintainer's new version tag after configuring namespace/signing access.

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
