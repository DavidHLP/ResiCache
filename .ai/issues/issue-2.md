# Issue #2 — Real Redis Cluster slot co-location integration test

- **Issue:** https://github.com/DavidHLP/ResiCache/issues/2
- **Status:** COMMITTED
- **Priority:** P1 correctness
- **Dependencies:** none; production implementation appears present and needs real-topology proof
- **External work:** no assignee, comments, branch, or linked PR

## Current findings

- `DistributedLockManager#buildLockKey` already distinguishes cluster mode and preserves or creates a hash tag.
- Unit tests already cover no-tag wrapping, existing-tag preservation, and slot equality by key construction.
- Existing `DistributedLockIntegrationTest` uses standalone Redis, so the Issue's real Cluster acceptance is not yet proved.
- Testcontainers dependency must remain pinned to `1.20.4`; target test execution and zero skips must be checked explicitly.

## Acceptance criteria

1. A real `redis:7` Cluster topology starts under Testcontainers.
2. A sync-enabled cache PUT/get-loader path obtains a Redisson distributed lock.
3. Cache key and lock key report the same `CLUSTER KEYSLOT` and map to the same node.
4. The real operation completes without `CROSSSLOT`.
5. Test report confirms target test count > 0 and skipped = 0.

## Implementation plan

- Add an isolated `AbstractRedisClusterIntegrationTest` rather than destabilizing all standalone Redis ITs.
- Reuse production `DistributedLockManager`/cache path; do not duplicate slot logic in the test.
- Add one focused Cluster integration class and only the minimum fixture configuration required.
- Update cluster limitation/docs only after the proof passes.

## Files/modules involved

- `src/main/java/.../protection/breakdown/DistributedLockManager.java` (expected read-only)
- `src/test/java/.../integration/AbstractRedisClusterIntegrationTest.java`
- `src/test/java/.../integration/*Cluster*IntegrationTest.java`
- Redis compatibility/cache lifecycle wiki or docs identified during investigation

## Tests required

- Existing `DistributedLockManagerTest`.
- New real Cluster IT with container-start and no-skip evidence.
- Full integration regression.

## Validation result

- Real `redis:7-alpine` container started and formed a three-master Cluster (`cluster_state:ok`).
- `RedisClusterSlotIntegrationTest`: 1 test, 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`.
- Production annotation path acquired a live Redisson lock; test read its actual Redis key, compared both server-side `CLUSTER KEYSLOT` values, and executed a same-slot two-key command without `CROSSSLOT`.
- Target test also proved the cache value was written after lock release.
- `DistributedLockManagerTest` + Cluster IT: 26 tests, 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`.
- Checkstyle: 0 violations; `git diff --check`: clean.
- The unchanged standalone `DistributedLockIntegrationTest` cannot start its pre-existing WSL2 fixture because this workstation lacks `socat`; rerunning it alone reproduces the same `Cannot start socat` before test execution. The new Cluster fixture does not depend on or weaken that asset.
- Review and commit pending.

## Review findings

Independent reviewer jobs again failed to yield within the bounded review window and were cancelled; the maintainer process completed the frozen Standards + Spec review directly.

- **critical/high/medium:** none.
- Topology validity: three Redis server processes form a real Cluster with node IDs and complete slot ownership; teardown requires `cluster_state:ok`.
- Acceptance proof: the test observes a live production Redisson lock key, asks Redis for both `CLUSTER KEYSLOT` values, and executes a dual-key `EXISTS`; a slot mismatch would produce `CROSSSLOT` before key existence is evaluated.
- Lifecycle/concurrency: loader release is in `finally`, preventing an assertion failure from leaving the async loader blocked.
- Compatibility: `TestRedisConfiguration` keeps its original single-server branch and selects Cluster only when `resi-cache.redis.mode=cluster`; production code is unchanged.
- Documentation matches the exact topology and server-side assertions.

## Commit / PR

Local Issue commit: `test(redis): prove cluster lock slot co-location`. Remote PR/Issue closure requires GitHub Write Gate approval.

## Remaining work

Include #2 in the final remote-write review package.
