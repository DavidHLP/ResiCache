# Issue #2 — Real Redis Cluster slot co-location integration test

- **Issue:** https://github.com/DavidHLP/ResiCache/issues/2
- **Author:** `DavidHLP`
- **Type:** Issue (P1 Correctness)
- **Status (GitHub):** CLOSED (Closed on 2026-07-26 by commit `037ffe4` via PR #6)
- **Status (Local):** RESOLVED & COMMITTED
- **Labels:** `good first issue`
- **Comments count:** 1 (`DavidHLP`)

## Context & Objectives

Verify cache-key and Redisson distributed lock-key slot co-location against a real three-master Redis Cluster under Testcontainers to guarantee that single-flight synchronized locking does not trigger `CROSSSLOT Keys in request don't hash to the same slot`.

## Acceptance Criteria

1. Real `redis:7` Cluster topology starts under Testcontainers.
2. A sync-enabled cache PUT/get-loader path obtains a Redisson distributed lock.
3. Cache key and lock key report identical `CLUSTER KEYSLOT` values and map to the exact same cluster master node.
4. Two-key Redis operations complete without `CROSSSLOT`.
5. Zero tests skipped.

## Validation & Code Evidence

- **Commit:** `037ffe4` (`test(redis): prove cluster lock slot co-location`).
- **Test Class:** `src/test/java/io/github/davidhlp/spring/cache/redis/integration/RedisClusterSlotIntegrationTest.java`.
- **Result:** Real 3-master Cluster reached `cluster_state:ok`, asserted identical slot IDs for `{cache:lock:...}` and key, passed with 0 failures/skips.
