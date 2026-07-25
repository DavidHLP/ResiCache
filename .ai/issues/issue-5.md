# Issue #5 — Serialization migration CLI (`shadow-read → dual-write → cutover`)

- **Issue:** https://github.com/DavidHLP/ResiCache/issues/5
- **Status:** COMMITTED
- **Priority:** P1 compatibility
- **Dependencies:** existing envelope serializer and pre-flight probe
- **External work:** no assignee, comments, branch, or linked PR

## Current findings

- `SecureJacksonRedisSerializer` always writes `VersionEnvelope.CURRENT_VERSION` and rejects/returns null for non-envelope legacy values depending on error policy.
- `SerializationPreFlightProbe` scans a bounded sample and warns about non-envelope values; it is diagnostic only.
- `VersionEnvelope` and whitelist/type-id validation are existing security boundaries that migration must not bypass.
- The requested capability is explicitly a CLI workflow, not only runtime serializer fallback.

## Acceptance criteria

1. Operator-visible CLI supports shadow-read, dual-write, and cutover phases for both `GenericJackson2JsonRedisSerializer` and JDK-serialized legacy values.
2. Legacy detection and decoding preserve ResiCache whitelist/security policy.
3. Migration is idempotent, supports partial runs/resume, preserves TTL, and handles mixed legacy/envelope datasets.
4. Dual-write/partial failure and rollback behavior are explicit and observable.
5. Cutover does not require cache flush or full-miss deployment.
6. Real Redis integration tests inspect stored bytes and TTL; target test count > 0 and skipped = 0.
7. Operator docs specify dry-run, key selection/scope, failure output, and safe rollback.

## Implementation plan

- Trace Redis serialization configuration, cache read/write bytes boundary, and pre-flight probe.
- Design a bounded CLI migration engine around SCAN batches and compare/idempotent writes; avoid placing migration phase state in the hot serializer path unless acceptance requires it.
- Use explicit legacy decoder selection and safe conversion to current envelope.
- Add metrics/log summary with bounded dimensions only.
- Add real Redis integration matrix for legacy JSON, JDK, mixed data, TTL, partial failure/resume, and corrupted/untrusted payload.
- Update serialization/configuration/operator docs.

## Files/modules involved

- `src/main/java/.../serialization/**`
- `src/main/java/.../config/SerializationPreFlightProbe.java`
- `src/main/java/.../config/RedisProCacheProperties.java`
- CLI/runner package determined by current Spring Boot packaging conventions
- serializer unit tests and `integration/*SerializationMigration*IntegrationTest.java`
- `wiki/modules/serialization.md`, `wiki/modules/configuration.md`, CLI operator guide

## Tests required

- Decoder/security unit tests.
- CLI planning/dry-run/idempotency tests.
- Real standalone Redis migration integration matrix with no skips.
- Full serializer and integration regression.

## Validation result

- Real three-master `redis:7-alpine` Cluster started and remained `cluster_state:ok`.
- Post-review migration + serializer/security regression: 37 tests, 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`.
- The 8 migration ITs prove shadow-read zero writes, dual-write sidecars + TTL + idempotency, bounded partial-run resume, cluster-wide scanning across masters, JDK cutover/backup/rollback, mixed valid/rejected data, dry-run, and refusal to overwrite post-cutover concurrent writes.
- Generic Jackson/JDK legacy security has 5 focused unit tests, including custom type-property bypass regression and JDK whitelist rejection.
- `./mvnw -B checkstyle:check`: 0 violations; `git diff --check`: clean.

## Review findings

Independent reviewer jobs failed to yield within the bounded window and were cancelled. The maintainer completed the frozen Standards + Spec checklist in-process. Review found and fixed high-risk GET→SET races in cutover/rollback with single-key Lua compare-and-set, protected missing-source rollback with SET NX, added post-cutover-write refusal, hardened Generic Jackson's custom type-property path, made suffix handling binary-safe, removed raw key logging, iterated all Redis Cluster masters, and made `maxKeys` resumable by counting only not-yet-completed actionable keys.

- **critical/high/medium remaining:** none.
- Legacy detection: `SerializationPreFlightProbe` + safe decoder identify envelope/Generic Jackson/JDK.
- Shadow read / dual write / cutover / rollback: all have real Redis Cluster tests and explicit write semantics.
- Idempotency / partial / mixed-version: completed sidecars do not consume resume quota; envelopes are validated and skipped; mixed valid/rejected values continue with failure counts.
- Concurrency/failure: CAS refuses concurrent source changes; per-key failures are isolated and counted; CLI exits unsuccessfully when `failed>0`.
- Observability: bounded `phase`/`outcome` tags only, plus aggregate report; key logs use non-reversible hash fingerprints.
- Operator UX/docs: dry-run, patterns, limits, phases, deployment boundary, rollback window and sidecar retention documented in `docs/serialization-migration.md` and synchronized wiki/config/compatibility pages.
- CLI smoke: minimal context starts successfully and reaches the migration engine; without a local Redis it then fails specifically with `RedisConnectionFailureException`, proving bean/config wiring rather than a context error.

## Commit / PR

Local Issue commit: `feat(migration): add serialization cutover workflow`. Remote PR/Issue closure requires GitHub Write Gate approval.

## Remaining work

Include #5 in the final remote-write review package.
