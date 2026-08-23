# Issue #3 — Minimal JMH module and measured performance baseline

- **Issue:** https://github.com/DavidHLP/ResiCache/issues/3
- **Status:** RESOLVED
- **Priority:** P1 performance
- **Dependencies:** None
- **External PR:** https://github.com/DavidHLP/ResiCache/pull/7 by `Shubh2-0`

## Summary of Resolution

- Imported and integrated the `resicache-bench` standalone Maven module and `PERFORMANCE.md`.
- Code review identified and fixed 3 compile/API bugs from original draft PR #7:
  1. `SyncLockBenchmark`: Fixed `setTimeoutSeconds(5)` -> `setTimeout(5)` and `setUnit(TimeUnit.SECONDS)`.
  2. `BloomFilterBenchmark`: Removed stale `BloomGate` import, aligned with 4-arg `BloomFilterConfig` constructor and `LocalBloomIFilter(config, hashStrategy)` API.
  3. `TtlJitterBenchmark`: Fixed `DefaultTtlPolicy` constructor and `calculateFinalTtl(baseTtlSeconds, true, jitterRatio)` call semantics.
  4. Root `pom.xml`: Configured `maven-compiler-plugin` annotation processor paths for Lombok 1.18.34 so properties getters/setters compile cleanly across modules.

## Validation result

1. `mvn install -DskipTests -Djacoco.skip=true && mvn -f resicache-bench/pom.xml clean package -DskipTests`:
   - `resicache-bench/target/resicache-bench.jar` produced successfully.
2. JMH smoke tests executed and verified:
   - `BloomFilterBenchmark.bloomMightContain_hit`: passed.
   - `SyncLockBenchmark.noSync`: passed.
   - `TtlJitterBenchmark.ttlBaseline`: passed.
3. Full repository test suite (`mvn clean test -B`):
   - 852 unit + integration tests, 0 failures, 0 errors, 0 skipped.
   - Core test regression unaffected by benchmark module.

## Commit / PR

- Commit: `feat(bench): integrate resicache-bench module with SyncLock, BloomFilter, and TtlJitter benchmarks`
- Resolves #3; Closes #7.
