---
phase: 01-tech-debt
plan: 01
subsystem: eviction
tags: [lock-free, atomicinteger, conc尿rency]

key-files:
  created: []
  modified:
    - src/main/java/io/github/davidhlp/spring/cache/redis/strategy/eviction/support/TwoListLRU.java
    - src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/support/refresh/ThreadPoolPreRefreshExecutor.java
    - src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/chain/handler/PreRefreshHandler.java
    - src/main/java/io/github/davidhlp/spring/cache/redis/core/writer/RedisProCacheWriter.java

requirements-completed:
  - TECH-01
  - TECH-02
  - TECH-03
  - TECH-04

# Phase 1: Tech Debt Summary

**Lock-free TwoListLRU counters, independent cleanup scheduler, TTL guard in async refresh, and eager-hungry chain singleton**

## Performance

- **Tasks:** 4 plans, all complete
- **Files modified:** 4
- **Commits:** 4

## Accomplishments

1. **TwoListLRU lock-free reads** — Replaced `activeSize`/`inactiveSize` int fields with `AtomicInteger` counters. `getActiveSize()` and `getInactiveSize()` now use `AtomicInteger.get()` instead of acquiring a `ReadWriteLock.readLock()`. Write operations update counters atomically via `incrementAndGet()`/`decrementAndGet()`.

2. **Independent cleanup scheduler** — Added `ScheduledExecutorService` to `ThreadPoolPreRefreshExecutor` that runs `cleanFinished()` at a fixed 30s interval. `getActiveCount()` no longer triggers cleanup, decoupling cleanup from polling.

3. **TTL guard in async refresh** — Added `getRemainingTtl()` check in `scheduleAsyncRefresh()` before shortening TTL. If remaining TTL is below `REFRESH_GRACE_PERIOD_SECONDS`, the shorten operation is skipped to avoid refreshing already-expired data.

4. **Eager-hungry chain singleton** — Replaced DCL pattern (`volatile` + double-checked locking) in `getChain()` with a simpler `final` field initialized at construction time. Removes lock overhead on every cache operation.

## Task Commits

1. **T-01/T-02/T-03/T-04/T-05: TwoListLRU AtomicInteger** - `b6e4ea2` (fix: replace activeSize/inactiveSize read-lock with AtomicInteger)
2. **T-01/T-02/T-03: ThreadPoolPreRefreshExecutor scheduler** - `4b1773f` (feat: add independent cleanup scheduler for cleanFinished)
3. **T-01: PreRefreshHandler TTL check** - `3097bf3` (fix: add TTL check before shortening TTL in async refresh)
4. **T-01/T-02/T-03: RedisProCacheWriter DCL simplification** - `441beda` (refactor: simplify getChain() from DCL to eager-hungry singleton)

## Decisions Made

- Kept `activeSize`/`inactiveSize` int fields as max-size parameters; only the live counter is now `AtomicInteger`
- `cleanFinished()` remains `private` — called by scheduler and available for direct invocation if needed
- `REFRESH_GRACE_PERIOD_SECONDS` (5s) is used as both the TTL threshold for skipping refresh and the grace period for shortening TTL
- Chain is `final` and initialized inline (`= buildChain()`) so it is constructed before any thread could see the reference

## Deviations from Plan

None — all 4 plans executed as specified.

## Issues Encountered

1. **Double counter increment in TwoListLRU.put()** — `activeSizeCounter.incrementAndGet()` was called both in `put()` and inside `addToActiveHeadUnsafe()`. Fixed by removing the outer increment and relying solely on the inner `addToActiveHeadUnsafe()` counter. Found during test run (`expected: 2 but was: 4`).

## Next Phase Readiness

- All phase 1 success criteria met: lock-free reads, independent cleanup, TTL guard, simplified chain
- No blocking dependencies for downstream phases

---
*Phase: 01-tech-debt*
*Completed: 2026-04-24*
