# Issue #3 — Minimal JMH module and measured performance baseline

- **Issue:** https://github.com/DavidHLP/ResiCache/issues/3
- **Author:** `DavidHLP`
- **Type:** Issue (P1 Performance)
- **Status (GitHub):** OPEN
- **Status (Local):** RESOLVED & COMMITTED (Commit `c26e0dc` + `91285dd`)
- **Labels:** `help wanted`, `performance`
- **Comments count:** 4 (`Shubh2-0` ×2, `DavidHLP` ×2)
- **Associated PR:** PR #7 (Draft by `Shubh2-0`)

## Timeline & Collaboration Summary

1. **2026-07-21 (`Shubh2-0`):** Volunteered to implement the `resicache-bench` module using JMH annotation processor and 3 benchmark suites.
2. **2026-07-26 (`DavidHLP`):** Acknowledged intent and requested ETA/draft PR.
3. **2026-07-27 (`Shubh2-0`):** Opened draft PR #7 containing `resicache-bench` skeleton with `SyncLock`, `BloomFilter`, `TtlJitter` and preliminary `PERFORMANCE.md`.
4. **2026-07-28 (`DavidHLP`):** Requested changes on PR #7:
   - Scope alignment: add (1) chain pass-through vs Spring-native `@Cacheable`, (2) per-handler additive cost, (3) real raw JMH JSON output to back `PERFORMANCE.md`.

## Defect Triage & Local Implementation

During maintainer review and integration:
1. **API / Compile Bugs in PR #7 Draft Fixed:**
   - `SyncLockBenchmark`: Fixed `setTimeoutSeconds(5)` -> `setTimeout(5)` + `setUnit(TimeUnit.SECONDS)`.
   - `BloomFilterBenchmark`: Removed stale `BloomGate` import, aligned with current 4-arg `BloomFilterConfig` constructor and `LocalBloomIFilter` API.
   - `TtlJitterBenchmark`: Fixed `DefaultTtlPolicy` constructor and `calculateFinalTtl(Long, boolean, float)` call semantics.
   - Root `pom.xml`: Configured `maven-compiler-plugin` Lombok 1.18.34 annotation processor path.
2. **Missing Scenarios Implemented:**
   - Added `ChainPassThroughBenchmark.java` (Direct method vs Spring-native map lookup vs ResiCache ChainEngine pass-through).
   - Added `HandlerAdditiveCostBenchmark.java` (Isolated marginal overhead for 1 to 5 handlers).
3. **Live Measurement Verification:**
   - Executed live JMH benchmark suite on Linux / OpenJDK 21.0.2 / Intel Core Ultra 7 265K.
   - Generated raw output `resicache-bench/target/jmh-results.json`.
   - Fully updated `PERFORMANCE.md` with measured throughputs, latencies, and SLO validations.

## Validation Results

- `mvn clean test -B`: 852 unit + integration tests pass (0 failures, 0 errors, 0 skipped).
- `mvn -f resicache-bench/pom.xml clean package -DskipTests`: Fat-jar builds cleanly.
- Live JMH benchmarks verified:
  - Bloom filter hit: 5.85 M ops/s (SLO ≥ 5.0 M ops/s)
  - SyncLock 8-thread single-flight: 81.1 M ops/s
  - TTL Jitter compute: 54.8 M ops/s (SLO ≥ 10.0 M ops/s)
  - Chain pass-through: 30.97 M ops/s (~32.2 ns)
  - Marginal cost per handler: +2.5 ~ +3.8 ns/node
