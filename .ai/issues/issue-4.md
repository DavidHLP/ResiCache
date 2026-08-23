# Issue #4 — Per-handler Micrometer tags on `resicache.chain.execute`

- **Issue:** https://github.com/DavidHLP/ResiCache/issues/4
- **Author:** `DavidHLP`
- **Type:** Issue (P0 Observability)
- **Status (GitHub):** CLOSED (Closed on 2026-07-26 by commit `01cca01` via PR #6)
- **Status (Local):** RESOLVED & COMMITTED
- **Labels:** `help wanted`
- **Comments count:** 1 (`DavidHLP`)

## Context & Objectives

Add bounded tag dimensions (`handler`, `decision`, `cacheName`) to `resicache.chain.execute` timer without unbounded high-cardinality tags (such as raw `redisKey`, user input, exception message, or dynamic IDs) to ensure production metric scalability and per-handler latency observability.

## Acceptance Criteria

1. `resicache.chain.execute` is queryable by bounded `handler`, `decision`, and `cacheName` tags.
2. `handler` is drawn from finite registered handlers; `decision` is one of `CONTINUE`, `SKIP_ALL`, `TERMINATE`.
3. Strict zero high-cardinality dynamic tags.
4. Each timer sample measures the corresponding handler invocation duration, not duplicate whole-chain duration.
5. Null registry remains safe no-op.

## Validation & Code Evidence

- **Commit:** `01cca01` (`fix(observability): add bounded chain timer dimensions`).
- **Implementation:** `src/main/java/io/github/davidhlp/spring/cache/redis/chain/observer/ChainTimerChainObserver.java`.
- **Test Class:** `src/test/java/io/github/davidhlp/spring/cache/redis/chain/observer/ChainObserverTest.java` (verified meter count remains constant when varying `redisKey`).
