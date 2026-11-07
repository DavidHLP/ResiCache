# ResiCache Performance Benchmarks

This document preserves historical JMH throughput measurements and explains
how to run the current benchmarks. The numbers are not current-build results
or enforced performance SLOs.
All measurements were produced by the `resicache-bench` module using JMH 1.37 on local development hardware.

---

### Environment

| Property | Value |
|---|---|
| JDK | OpenJDK 21.0.2+13 (Temurin / 64-Bit Server VM) |
| OS | Linux 7.1.9 (x86_64) |
| CPU | Intel Core Ultra 7 265K (20 cores, 20 threads) |
| Heap | `-Xms512m -Xmx1g` |
| JMH Warmup / Measurement | 1-3 iterations × 1 s | JMH Fork: 1 |

---
> **Baseline status:** The results below are a historical baseline for the
> environment recorded above, not a release or SLO certification. A later local
> smoke run on JDK 21.0.12.1 produced non-comparable values (Bloom hit
> **4.86M ops/s**, `syncLocalOnly_8threads` **20.8M ops/s**, and chain
> pass-through **24.8M ops/s**). That run confirms the benchmark executable
> works; it does not validate or replace this historical table.


The historical benchmark identifiers below predate a scope correction. The
`springNativeCacheLookup` result is a direct `ConcurrentHashMap.get()` baseline,
not a Spring interceptor or Redis cache measurement. The additive handler
results measure dispatch through pass-through nodes (the former TTL node used
GET and did not calculate TTL); they do not measure full protection cost.
`ttlJitter_concurrent_uniformity` measures concurrent computation throughput,
not distribution correctness. Current method names state those scopes.
`CacheSerializationBenchmark` now measures v2 serializer/codec/storage round trips
at several payload sizes, excluding Redis network I/O. Its mapper codec is a
reference for adaptation cost; the copied facade recreates the former facade path.
These are not release SLO gates.

## Interpreting performance evidence

Evaluate three separate questions:

- **Measurement validity and scope:** judge what each method actually measures
  under its stated setup. Missing production evidence does not by itself
  invalidate a bounded diagnostic or microbenchmark.
- **Observed cost:** compare only within the recorded environment and baseline.
  The annotation-path diagnostic below observed higher sync+Bloom hit latency;
  its loopback Redis, in-process loader and shared cache manager limit that
  comparison.
- **Application benefit:** reduced expensive-miss work under contention needs
  application-load evidence. A local hit-cost penalty does not disprove that
  potential benefit, and these measurements do not establish it. Real database,
  remote-RTT and sustained multi-instance benefit remain unproven.

## Running the Benchmarks

```bash
# 1. Install ResiCache core into your local Maven cache
./mvnw install -DskipTests -Djacoco.skip=true -B

# 2. Build the fat-jar
./mvnw -f resicache-bench/pom.xml clean package -DskipTests -B

# 3. Run all benchmarks (duration depends on methods and parameter combinations)
java -jar resicache-bench/target/resicache-bench.jar -f 1 -wi 1 -i 2 -w 1s -r 1s -rf json -rff resicache-bench/target/results.json

# 4. Run a specific benchmark suite
java -jar resicache-bench/target/resicache-bench.jar BloomFilterBenchmark
```

---

## Current benchmark names and CI smoke

Use JDK 21 for both build and execution. List runnable methods with
`java -jar resicache-bench/target/resicache-bench.jar -l`.
Historical identifiers below retain their original scores; renamed methods are
not newly measured results:

| Historical identifier | Current identifier / scope |
|---|---|
| `springNativeCacheLookup` | `concurrentMapLookup`: direct map access |
| `ttlJitter_concurrent_uniformity` | `ttlJitter_concurrentThroughput`: concurrent computation |
| `cost_1_handler_ttl` through `cost_5_handlers_full` | `cost_1_passthrough` through `cost_5_passthrough`: handler dispatch |

The current serialization suite additionally measures `storageRoundTrip` at
multiple payload sizes, without Redis network traffic. CI verifies the saved
core candidate, builds this module, and runs only a bounded protocol smoke:

```bash
java -jar resicache-bench/target/resicache-bench.jar \
  'CacheSerializationBenchmark.storageRoundTrip' \
  -p payloadBytes=64 -wi 0 -i 1 -r 100ms -f 1 -foe true \
  -jvmArgs '-Xms128m -Xmx256m'
```

That smoke proves executable protocol behavior, not throughput thresholds.

## Historical benchmark results

### Benchmark 1 — Bloom Filter (`BloomFilterBenchmark`)
Measures JVM-level `LocalBloomIFilter` cache-penetration gate throughput.

| Benchmark | Params (bitSize, hashFunc) | Score (ops/s) | Interpretation | Status |
|---|---|---|---|---|
| `bloomMightContain_hit` | 8388608, 3 | **5,850,803** | True-positive fast path (bit-array read only) | Historical |
| `bloomMightContain_miss` | 8388608, 3 | **5,670,175** | Local filter negative; no DB load measured | Historical |
| `bloomPut` | 8388608, 3 | **4,296,647** | Insertion throughput on cache write | Historical |

---

### Benchmark 2 — SyncLock (`SyncLockBenchmark`)
Measures single-flight leader-follower synchronization under cache breakdown / thundering herd conditions.

| Benchmark | Threads | Score (ops/s) | Interpretation | Status |
|---|---|---|---|---|
| `noSync` | 1 | **1,787,267** | Baseline: direct CPU-bound loader substitute | Reference |
| `syncLocalOnly_8threads` | 8 | **81,128,097** | Leader-follower coordination with 8 concurrent threads | Historical |
| `syncContended_32threads` | 32 | **455,369,392** | Worst-case stampede (32 threads hammered on 1 key) | Historical |

---

### Benchmark 3 — TTL Jitter (`TtlJitterBenchmark`)
Measures `TtlPolicy` Gaussian random variance calculation to prevent cache avalanche.

| Benchmark | jitterRatio | Score (ops/s) | Interpretation | Status |
|---|---|---|---|---|
| `ttlBaseline` | 0.1 | **478,945,558** | Direct unjittered return baseline | Reference |
| `ttlJitter_compute` | 0.1 | **54,806,459** | Gaussian jitter per cache put (~18 ns overhead) | Historical |
| `ttlJitter_compute` | 0.2 | **52,697,903** | Configurable ratio variance sweep | Historical |
| `ttlJitter_concurrent_uniformity` | 0.1 (8 threads) | **5,551,344** | Concurrent jitter computation throughput | Historical |

---

### Benchmark 4 — Chain Pass-Through (`ChainPassThroughBenchmark`)
Measures ResiCache `ChainEngine` pass-through execution against direct method calls and a direct concurrent-map lookup.

| Benchmark | Score (ops/s) | Avg Latency | Interpretation |
|---|---|---|---|
| `directInvocation` | **5,109,139,260** | ~0.2 ns | Pure in-register method return |
| `springNativeCacheLookup` | **856,844,662** | ~1.17 ns | `ConcurrentHashMap.get()` baseline |
| `chainPassThrough` | **30,970,489** | ~32.2 ns | `ChainEngine.execute` traversing 1 handler node |

---

### Benchmark 5 — Marginal Cost per Handler (`HandlerAdditiveCostBenchmark`)
Measures the additive overhead per installed handler in the execution chain.

| Benchmark | Installed Handlers | Score (ops/s) | Marginal Delay |
|---|---|---|---|
| `cost_1_handler_ttl` | 1 (dispatch) | **31,466,289** | ~31.8 ns baseline |
| `cost_2_handlers` | 2 (dispatch) | **28,108,769** | +3.8 ns |
| `cost_3_handlers` | 3 (dispatch) | **25,716,085** | +3.3 ns |
| `cost_4_handlers` | 4 (dispatch) | **24,240,836** | +2.4 ns |
| `cost_5_handlers_full` | 5 (dispatch) | **22,488,609** | +3.2 ns |

*In this historical run, additional pass-through dispatch nodes added roughly
2.4–3.8 ns each. These figures do not include real protection work or Redis I/O.*

## Real Redis annotation-path diagnostic

`CacheLatencySmokeBenchmarkIntegrationTest.compareAnnotationPathsWithRealRedisAndVerifyNoReloads`
compares native annotation and sync+Bloom annotation hits through Spring AOP and
real Testcontainers Redis. Each path warms 50 calls and measures 1,000 calls;
returned values and zero loader calls during measurement are asserted.
A local JDK 21.0.13 / Docker 29.9.2 run measured native p50/p99
212.39/777.57 us and sync+Bloom 616.40/1714.32 us. These single-run diagnostic
numbers include loopback Redis and serialization, use an in-process loader
instead of a real database, and do not establish an SLO or a production benefit.
The native annotation path still uses ResiCache's configured cache manager;
this is not a comparison against an independent Spring Redis implementation.
Real database contention, remote network latency, Redis CPU/memory, and sustained
multi-instance throughput remain unmeasured. Built-in Bloom negatives now allow
lookup; an authoritative custom membership filter is required for rejection.
