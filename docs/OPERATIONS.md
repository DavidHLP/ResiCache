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
end-user request. This override supplies the parsed file configuration; the
normal library topology/pool settings are not then applied. Trusted Redisson YAML tags and codec
extensions remain supported; endpoint checks do not make YAML safe for untrusted
input.

For library-created Redisson clients, `tls-required=true` checks every configured
endpoint in the effective parsed single, cluster, sentinel, replicated or
master/slave topology for `rediss://` before client creation. This checks the
parsed Config rather than YAML text or a serialization roundtrip. Host-supplied
Redisson clients and Spring Data/Lettuce TLS remain host-owned; configure Lettuce
TLS independently. With a YAML override, binding defers the generated
`tls-enabled` requirement to this parsed endpoint check; secure YAML can use
`tls-enabled=false`, while plaintext YAML is rejected when TLS is required.
Without an override, required TLS still requires `tls-enabled=true`.

Redisson is optional until an operation requests distributed synchronization.
With no distributed `LockManager`, `sync=true` fails closed by default.
`resi-cache.sync-lock.local-only=true` is an explicit single-JVM degradation
and must not be treated as multi-instance protection.

## Redisson major-version migration

The core stays on Redisson 3.50 while its existing configuration contract is
required. Do not treat the benchmark module's separate dependency version as
approval to upgrade the core. ResiCache stores cache values through Spring
Data Redis and its own serializer; its Redisson client is used for locks.
Host applications may also use that client for values, maps, or other objects,
so establish actual usage before deciding whether a codec migration is needed.

Perform this inventory on operator-controlled copies, without starting the
application against production Redis:

1. Record the exact old application artifact, dependency tree, YAML file,
   environment/property inputs, codec and custom extension class versions.
   Protect secrets and do not attach raw configs or cache values to an issue.
2. Locate legacy `retryInterval`, mapper tags in `org.redisson.api`, removed
   codecs (`SnappyCodec`, `FstCodec`, `FuryCodec`, `MarshallingCodec`), and
   custom classes implementing the former mapper interfaces. A text search is
   an inventory aid, not a complete YAML parser or compatibility check.
3. Exercise the actual application configuration and representative old bytes
   in an isolated copy with the complete application classpath. Parsing a
   trusted custom YAML tag may instantiate its class; run this in a sandbox,
   not a production process. Test resolved variables, literal strings, all
   deployment modes, custom classes and both rolling-upgrade directions.

For built-in mapper and retry settings only, prepare a **separate** candidate
configuration and review the semantic changes explicitly:

- Replace a server's `retryInterval: 2468` with a `retryDelay` tagged
  `!<org.redisson.config.ConstantDelay>` and `delay: "PT2.468S"`. Preserve
  the actual delay, not merely the field's presence. Apply under the relevant
  single, cluster, sentinel, master/slave, or replicated server section.
- Built-in `DefaultNameMapper`, `HostNatMapper`, and `HostPortNatMapper` move
  from `org.redisson.api` to `org.redisson.config`. Preserve their mapping
  properties and verify actual mapped host, port and name values.
- Do not perform a whole-file string replacement: class-looking text may be
  a password or other literal. Do not assume these changes port custom classes
  compiled against the removed interfaces; recompile and test those separately.
- Never silently replace `SnappyCodec` with `SnappyCodecV2`. Their byte framing
  differs. Other removed codecs also require an explicit compatibility design,
  old-byte validation, and maintenance/security review of any replacement.

For a deployment that actually stores Redisson-coded values, choose and review
an application-specific migration before writing anything: the old reader
reads old data, a validated new writer writes a separate namespace, and the old
namespace remains intact for rollback. Preserve remaining TTL and collection
semantics, handle concurrent writes and deletions, verify contents, and only
then cut over. A one-time copy is not continuous dual-write. Locks must continue
using the same names across live old/new instances; changing their namespace
would split synchronization. Do not copy active lock state as ordinary values.
If rollback must read new writes, test the reverse compatibility or maintain a
reviewed dual-write protocol throughout the rollback window. No cache flush,
in-place codec rewrite, or automatic migration is provided here.

The `SerializationMigrationCli` below migrates ResiCache/Spring serializer
envelopes, **not arbitrary Redisson codec payloads**. Do not use it to convert
Snappy data without a separate implementation and validation.

Run the same deterministic compatibility gates with both dependency versions:

```bash
./mvnw -Punit -Dredisson.version=3.50.0 \
  -Dtest=RedissonYamlCompatibilityTest,RedissonCodecCompatibilityTest clean test -B
./mvnw -Punit -Dredisson.version=4.8.0 \
  -Dtest=RedissonYamlCompatibilityTest,RedissonCodecCompatibilityTest clean test -B
```

These tests use synthetic fixtures and never connect to Redis. The 4.8 command
currently fails by design because the old contract is not preserved; it is a
reproduction of the blocker, not an optional gate to waive. Even a future pass
must be followed by the full integration and packaged-consumer checks plus
actual application migration/rollback validation. Retain the old artifact and
unchanged configuration until that process is complete.

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

Rollback also needs a coordinated window: stop writes and evictions for the
affected keys while validating and restoring them. A missing source is restored
from its retained backup; the operator cannot distinguish natural expiration
from an intentional application deletion. Confirm that restoring missing keys
matches the recovery goal before running `ROLLBACK`. Keep the backup namespace
reserved for the migration window and retain it until recovery is no longer
needed. `maxKeys` limits actionable keys, not total SCAN/GET work; use a narrow
pattern and monitor progress when most keys are already complete or invalid.

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
cache writes instead of cutting them over. For ResiCache keys, use the CLI only
when the decoded legacy value already has the complete compatible `CachedValue`
structure, including its nested value/envelope representation and metadata.
Verify concrete types and typed cache-hit behavior; serializer round-tripping
alone is insufficient. CLI report counters describe transformations, not
application cache hits. Field-level type metadata does not require global
default typing, and enabling that switch does not supply the missing wrapper.

The compatible typed-hit fixture in `SerializationMigrationIntegrationTest`
starts with the current implementation's `cache.put`, decodes its `CachedValue`
storage, re-encodes it with the legacy generic JSON serializer, and cuts it over
before reading through `CacheManager` / typed `Cache.get`. It covers that
constructed compatible format, the actual read path and remaining TTL, not a
corpus of bytes from historical applications or releases. Validate representative
bytes from the actual old application separately before migration.

### Write preflight and recovery

Before any write phase, inventory the complete intended source set and reserve
collision-free `shadow-suffix` and `backup-suffix` namespaces. No source key may
end in either suffix; every derived destination must be absent or verified as
belonging to this migration. Stop on unrelated or unverifiable destinations.
The CLI skips source keys ending in these suffixes. `DUAL_WRITE` uses UPSERT to
overwrite differing shadow bytes. `CUTOVER` creates backups with SET_IF_ABSENT;
an existing or race-winning backup is reusable only when its bytes match the
captured legacy value. A conflicting backup refuses that key's cutover without
changing the source or backup. Never automatically delete or overwrite such a
backup: it may belong to an already successful cutover. A dry run does not
detect namespace conflicts. Prevent other writers from creating reserved keys
throughout the migration.

Same-byte retries reuse the established backup without extending its TTL or
recovery window. If another migrator has already installed the exact expected
envelope, source replacement is a successful no-op. A backup established before
a failed source CAS remains available for operator inspection; do not remove it
opportunistically. Backup creation and source CAS are separate operations, not
a transaction or fencing protocol. Source and backup may occupy different Redis
Cluster slots; external backup mutation, deletion or expiry is not atomic with
source CAS. Coordinate application writes, evictions, cutover and rollback for
the entire recovery window.

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

Rollback leaves an already restored legacy value unchanged. Otherwise, it
replaces a non-null source only if its bytes exactly match the envelope derived
from the retained backup; newer legacy or envelope values are refused. It
recreates a missing source with SET_IF_ABSENT using the backup's remaining TTL.
This intentionally covers both DEL and natural expiry, which the operator
cannot distinguish from accidental loss, and can resurrect intentionally
evicted stale values. Quiesce writes and evictions, reconcile prior deletions
against the backup inventory, and exclude their backups before authorizing
rollback. Do not run rollback when restoring missing keys is unacceptable.
Backups may already have expired; there is no recovery promise after backup
expiry and rollback is not guaranteed for every key.

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

### Distributed lock lifetime

The built-in Redisson lock uses watchdog renewal rather than a fixed lease
calculated from the acquisition timeout. A slow loader retains its lock while
the client can renew it; acquisition timeout only bounds waiting to acquire.
Client death or loss of renewal still allows the lock to expire. This is cache
load coordination, not fencing for authoritative data writes.

Only the acquiring thread can close a built-in lock handle. Close calls unlock
directly, without an ownership pre-query that could bypass release/renewal cleanup.
The built-in lock stack keeps the handle for one additional owner-thread close
round after retryable failure: two rounds of at most three unlock attempts each.
An interrupted owner does not start the recovery round. After exhaustion or
interruption the stack discards the handle; the physical lock may still be held
and renewing. This is bounded recovery, not eventual release during an outage.
For direct handle callers, after bounded failure or interrupted retry sleep,
the owner may explicitly
retry close; interruption remains set. Retry must occur before that thread
reacquires the same key: Redisson client/thread ownership cannot distinguish a
later reentrant acquisition. Successful unlock or reported ownership loss makes
the handle terminal. There is no background retry, force-unlock, fixed lease or
fencing. Controlled tests inject faults around real unlock commands; they do not
establish recovery across arbitrary partitions, lost responses or later reentry.
See [`DEVELOPMENT.md`](DEVELOPMENT.md#evidence-and-controlled-fixtures) for
fixture scope.
