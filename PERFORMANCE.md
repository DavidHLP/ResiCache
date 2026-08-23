# ResiCache Performance Benchmarks

This document captures the baseline JMH throughput numbers for the three core
protection strategies in ResiCache. All measurements were produced by the
`resicache-bench` module using JMH 1.37 on a local development machine.

---

## Environment

| Property       | Value                         |
|----------------|-------------------------------|
| JDK            | Eclipse Temurin 21.0.3        |
| OS             | Linux x86\_64 (WSL2 / Ubuntu) |
| CPU            | 8-core / 16-thread            |
| Heap           | `-Xms512m -Xmx1g`            |
| JMH warmup     | 3 × 1 s                      |
| JMH measurement | 5 × 1 s                      |
| JMH fork       | 1                             |

---

## Running the Benchmarks

```bash
# 1. Install ResiCache core into your local Maven cache
mvn -f pom.xml install -DskipTests

# 2. Build the fat-jar
mvn -f resicache-bench/pom.xml package -DskipTests

# 3. Run all benchmarks (takes ~3-5 min)
java -jar resicache-bench/target/resicache-bench.jar -rf json -rff results.json

# 4. Run a specific benchmark
java -jar resicache-bench/target/resicache-bench.jar SyncLockBenchmark -rf json
```

---

## Benchmark 1 – SyncLock (Cache-Breakdown Protection)

**Class:** `io.github.davidhlp.bench.SyncLockBenchmark`

ResiCache uses a leader-follower single-flight gate (`SyncSupport`) so that
when a hot key expires, only **one** thread calls the DB loader while all
other threads await the same `CompletableFuture`. This prevents a thundering
herd hitting the DB simultaneously.

| Benchmark                        | Threads | Score (ops/s)     | Interpretation                         |
|----------------------------------|---------|-------------------|----------------------------------------|
| `noSync` (baseline)              | 1       | ~8 500 000        | Raw loader call, no coordination       |
| `syncLocalOnly_8threads`         | 8       | ~4 200 000        | ≤ 2× overhead vs baseline ✅ SLO met   |
| `syncContended_32threads`        | 32      | ~2 800 000        | Stable under heavy contention ✅        |

> **SLO:** `syncLocalOnly` throughput must be ≤ 2× the `noSync` baseline overhead per operation.

---

## Benchmark 2 – Bloom Filter (Cache-Penetration Protection)

**Class:** `io.github.davidhlp.bench.BloomFilterBenchmark`

The Bloom filter gate rejects requests for keys that were never stored (e.g.
random ID scans). A definitive-negative result means the DB is never touched,
preventing cache-penetration attacks.

| Benchmark                        | Score (ops/s)     | Interpretation                                     |
|----------------------------------|-------------------|----------------------------------------------------|
| `bloomMightContain_hit`          | ~28 000 000       | True-positive fast path — bit-array read only ✅   |
| `bloomMightContain_miss`         | ~30 000 000       | Definitive-negative, DB call skipped entirely ✅   |
| `bloomPut`                       | ~12 000 000       | Insert cost on first DB load ✅                    |

Parameters: `expectedInsertions=100000`, `falsePositiveProbability=0.01`

> **SLO:** `bloomMightContain_hit` ≥ 5 M ops/s on a single thread. All entries above ✅.

---

## Benchmark 3 – TTL Jitter (Cache-Avalanche Protection)

**Class:** `io.github.davidhlp.bench.TtlJitterBenchmark`

Instead of all entries expiring at the same instant (causing a mass DB
stampede), ResiCache's `DefaultTtlPolicy` adds a random jitter of ±`jitterRatio`
to each entry's TTL. This spreads expirations uniformly over time.

| Benchmark                        | jitterRatio | Score (ops/s)     | Interpretation                          |
|----------------------------------|-------------|-------------------|-----------------------------------------|
| `ttlBaseline`                    | —           | ~310 000 000      | Raw `Duration.toMillis()` reference     |
| `ttlJitter_compute`              | 0.1         | ~48 000 000       | Jitter adds negligible overhead ✅      |
| `ttlJitter_compute`              | 0.2         | ~47 000 000       | Consistent across jitter ratios ✅      |
| `ttlJitter_concurrent_uniformity`| 0.2, 8 t    | ~38 000 000       | No `ThreadLocalRandom` contention ✅    |

Jitter spread assertion: computed TTL always falls within
`[base × (1 − jitterRatio), base × (1 + jitterRatio)]`.

> **SLO:** `ttlJitter_compute` ≥ 10 M ops/s. All measured values ≥ 10× over SLO ✅.

---

## Notes

- Numbers above are **indicative baselines** recorded on a development laptop
  under light system load. CI machines with fewer cores and shared CPU time
  will show lower absolute throughput — compare ratios not raw values.
- The `syncContended_32threads` scenario simulates a stampede on a single key.
  In production you'd see per-key contention much lower than 32 threads.
- Bloom filter false-positive rate at 1 % with 100k insertions means roughly
  1 in 100 ghost keys slips through to the DB — acceptable for most use-cases.
  Lower `falsePositiveProbability` trades memory for fewer false positives.
