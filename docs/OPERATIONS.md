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

Metrics and the Redis health indicator are opt-in. Metrics require both
`resi-cache.metrics.enabled=true` and the application's `MeterRegistry`; the
health indicator additionally requires the optional Actuator dependency and
the same metrics property to be enabled. Writer statistics and failure
reporting are bounded by the contracts in `STABILITY.md` and `COMPATIBILITY.md`;
pre-1.0 metric names and log wording are not a general compatibility promise.

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
run automatically at application startup. A serializer change without this
workflow can make existing values unreadable; a cache flush is not the only
rollback strategy and is not required by the documented migration flow.

## Release and publication boundary

The root POM is versioned independently from the benchmark POM. A `vX.Y.Z` tag
enters the release workflow, which validates SemVer, runs lint/docs/build gates,
updates the POM version in the workflow workspace, deploys with repository
credentials, and creates a GitHub release. Release credentials are configured
out of band; contributors must not add secrets to `release.yml`.

The current Boot 4 / Java 21 line is source-first and has no matching Maven
Central artifact in the repository's compatibility evidence. A local
`./mvnw install` is a consumer-development step, not a publication or release
claim. Same-line publication/signing and adopter evidence remain deferred in
the local task ledger.

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
