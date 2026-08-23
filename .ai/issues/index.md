# ResiCache Issue & PR Archive Ledger

Last synchronized: 2026-08-23
Repository baseline: `main` @ `HEAD`
GitHub repository: `DavidHLP/ResiCache`

## Status Overview

| Issue / PR | Title | Priority / Type | Local Status | Remote GitHub Status | Resolution & Evidence |
|---|---|---|---|---|---|
| [#1](https://github.com/DavidHLP/ResiCache/pull/1) | Add qodana CI checks | PR | CLOSED | CLOSED | CI configuration |
| [#2](issue-2.md) | Redis Cluster slot IT | Issue P1 correctness | RESOLVED | CLOSED | Validated real 3-master Redis Cluster slot co-location without CROSSSLOT (`037ffe4`) |
| [#3](issue-3.md) | JMH module & PERFORMANCE.md | Issue P1 performance | RESOLVED | OPEN | Integrated `resicache-bench` with 5 suites (SyncLock, Bloom, TtlJitter, ChainPassThrough, HandlerAdditiveCost) + live measurements in `PERFORMANCE.md` |
| [#4](issue-4.md) | Per-handler Micrometer tags | Issue P0 observability | RESOLVED | CLOSED | Added bounded tags (`handler`, `decision`, `cacheName`) to `resicache.chain.execute` (`01cca01`) |
| [#5](issue-5.md) | Serialization migration CLI | Issue P1 compatibility | RESOLVED | CLOSED | Added CLI & engine with shadow-read, dual-write, cutover, rollback (`d97f3fd`) |
| [#6](https://github.com/DavidHLP/ResiCache/pull/6) | Observability, Cluster, Migration | PR (Maintainer) | COMMITTED | OPEN | Implemented in local main (resolves #2, #4, #5) |
| [#7](https://github.com/DavidHLP/ResiCache/pull/7) | Add resicache-bench JMH module | PR (External Draft) | MERGED_AND_EXTENDED | OPEN | Fixed 3 API bugs, added 2 missing benchmark suites, measured local baseline |

## Validation & Verification Evidence

1. **Unit & Integration Suite**:
   - `mvn clean test -B`: 852 tests run, 0 failures, 0 errors, 0 skipped.
2. **JMH Benchmarks**:
   - `resicache-bench` fat-jar built via `mvn -f resicache-bench/pom.xml clean package -DskipTests`.
   - All 5 suites executed live (`resicache-bench/target/jmh-results.json` generated).
   - Real throughput numbers populated in `PERFORMANCE.md`.
3. **Repository Cleanliness**:
   - 0 TODO/FIXME markers.
   - Wave 3 package consolidation completed.
