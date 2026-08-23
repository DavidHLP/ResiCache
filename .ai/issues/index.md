# ResiCache Issue & PR Archive Ledger

Last synchronized: 2026-08-23
Repository baseline: `main` @ `HEAD`
GitHub repository: `DavidHLP/ResiCache`

## Status Overview

| Issue / PR | Title | Priority / Type | Status | Resolution |
|---|---|---|---|---|
| [#1](https://github.com/DavidHLP/ResiCache/pull/1) | Add qodana CI checks | PR | CLOSED | CI configuration |
| [#2](issue-2.md) | Redis Cluster slot IT | Issue P1 correctness | RESOLVED | Validated real 3-master Redis Cluster slot co-location without CROSSSLOT (`037ffe4`) |
| [#3](issue-3.md) | JMH module & PERFORMANCE.md | Issue P1 performance | RESOLVED | Integrated `resicache-bench` JMH suite (SyncLock, Bloom, TtlJitter) + `PERFORMANCE.md` (PR #7) |
| [#4](issue-4.md) | Per-handler Micrometer tags | Issue P0 observability | RESOLVED | Added bounded tags (`handler`, `decision`, `cacheName`) to `resicache.chain.execute` (`01cca01`) |
| [#5](issue-5.md) | Serialization migration CLI | Issue P1 compatibility | RESOLVED | Added CLI & engine with shadow-read, dual-write, cutover, rollback (`d97f3fd`) |
| [#6](https://github.com/DavidHLP/ResiCache/pull/6) | Resolve observability, cluster, migration | PR (Maintainer) | RESOLVED | Implemented in main (resolves #2, #4, #5) |
| [#7](https://github.com/DavidHLP/ResiCache/pull/7) | Add resicache-bench JMH module | PR (External) | RESOLVED | Fixed compile/API issues and merged into main (resolves #3) |

## Repository Health & Verification

- **Unit & Integration Tests**: 852 tests run, 0 failures, 0 errors, 0 skipped (`mvn clean test -B`).
- **JMH Benchmarks**: `resicache-bench` produces executable fat-jar `resicache-bench/target/resicache-bench.jar`, all 3 benchmarks verified via live runs.
- **Code Cleanliness**: 0 TODO/FIXME markers remaining, redundant classes and obsolete wiki references removed.
- **Wave 3 Deepening**: Package boundaries unified, chain engine and factory streamlined, TwoListLRU simplified.
