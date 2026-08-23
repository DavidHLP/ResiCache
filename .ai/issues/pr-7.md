# Pull Request #7 — Add `resicache-bench` JMH Module (SyncLock, BloomFilter, TtlJitter + PERFORMANCE.md)

- **PR:** https://github.com/DavidHLP/ResiCache/pull/7
- **Author:** `Shubh2-0` (External Contributor)
- **Base Branch:** `main`
- **Status (GitHub):** CLOSED (Closed on 2026-08-23; merged & extended via commits `c26e0dc` and `91285dd`)
- **Status (Local):** MERGED & EXTENDED in local `main`
- **Labels:** `changes requested`, `performance`
- **Comments count:** 1 (`DavidHLP` review request)
- **Reviews count:** 0
- **Resolves:** Issue #3

## Content of Original Draft PR #7

- Added `resicache-bench/pom.xml` with JMH 1.37 and shade plugin.
- Added 3 benchmark classes:
  1. `SyncLockBenchmark.java` (noSync baseline, syncLocalOnly 8T, syncContended 32T).
  2. `BloomFilterBenchmark.java` (mightContain hit/miss, put).
  3. `TtlJitterBenchmark.java` (ttlJitter compute, ttlBaseline, 8T uniformity).
- Added `PERFORMANCE.md` with estimated numbers.

## Review Feedback (Maintainer Changes Requested @ 2026-07-28)

1. **Scope Alignment:** Required adding (1) chain pass-through vs Spring-native `@Cacheable` baseline, and (2) per-handler additive cost benchmark to fulfill Issue #3.
2. **Data Authenticity:** Requested raw JMH JSON output (`jmh-results.json`) and exact hardware/command reproducible artifacts.

## Maintainer Integration & Fixes

1. **Compile & API Defect Fixes:**
   - Fixed `SyncLockProperties` API (`setTimeoutSeconds(5)` -> `setTimeout(5)` + `setUnit(TimeUnit.SECONDS)`).
   - Fixed `BloomFilterConfig` constructor (4-arg constructor instead of removed builder) and `LocalBloomIFilter` usage.
   - Fixed `DefaultTtlPolicy` usage (`new DefaultTtlPolicy()` + `calculateFinalTtl`).
   - Added Lombok 1.18.34 annotation processor configuration in root `pom.xml`.
2. **Missing Benchmark Suites Implemented:**
   - `ChainPassThroughBenchmark.java`: Direct call (5.11 G ops/s) vs Spring-native map lookup (856 M ops/s) vs ResiCache 1-node chain (30.97 M ops/s).
   - `HandlerAdditiveCostBenchmark.java`: Measured marginal additive latency per handler (+2.5 ~ +3.8 ns).
3. **Execution & Evidence Backfill:**
   - Full live execution produced `resicache-bench/target/jmh-results.json`.
   - `PERFORMANCE.md` updated with measured throughputs, latencies, and hardware specifications.
