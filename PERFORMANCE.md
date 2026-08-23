# ResiCache Performance Benchmarks

This document captures the baseline JMH throughput numbers for core protection strategies and chain execution in ResiCache.
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

## Running the Benchmarks

```bash
# 1. Install ResiCache core into your local Maven cache
mvn install -DskipTests -Djacoco.skip=true

# 2. Build the fat-jar
mvn -f resicache-bench/pom.xml clean package -DskipTests

# 3. Run all benchmarks (~1-2 min)
java -jar resicache-bench/target/resicache-bench.jar -f 1 -wi 1 -i 2 -w 1s -r 1s -rf json -rff results.json

# 4. Run a specific benchmark suite
java -jar resicache-bench/target/resicache-bench.jar BloomFilterBenchmark
```

---

## Benchmark Results

### Benchmark 1 — Bloom Filter (`BloomFilterBenchmark`)
Measures JVM-level `LocalBloomIFilter` cache-penetration gate throughput.

| Benchmark | Params (bitSize, hashFunc) | Score (ops/s) | Interpretation | Status |
|---|---|---|---|---|
| `bloomMightContain_hit` | 8388608, 3 | **5,850,803** | True-positive fast path (bit-array read only) | **OK** (SLO ≥ 5.0 M ops/s) |
| `bloomMightContain_miss` | 8388608, 3 | **5,670,175** | Definitive negative, DB load bypassed | **OK** |
| `bloomPut` | 8388608, 3 | **4,296,647** | Insertion throughput on cache write | **OK** (SLO ≥ 1.0 M ops/s) |

---

### Benchmark 2 — SyncLock (`SyncLockBenchmark`)
Measures single-flight leader-follower synchronization under cache breakdown / thundering herd conditions.

| Benchmark | Threads | Score (ops/s) | Interpretation | Status |
|---|---|---|---|---|
| `noSync` | 1 | **1,787,267** | Baseline: direct loader execution (~100 µs simulated work) | Reference |
| `syncLocalOnly_8threads` | 8 | **81,128,097** | Leader-follower coordination with 8 concurrent threads | **OK** |
| `syncContended_32threads` | 32 | **455,369,392** | Worst-case stampede (32 threads hammered on 1 key) | **OK** (gate remains stable) |

---

### Benchmark 3 — TTL Jitter (`TtlJitterBenchmark`)
Measures `DefaultTtlPolicy` Gaussian random variance calculation to prevent cache avalanche.

| Benchmark | jitterRatio | Score (ops/s) | Interpretation | Status |
|---|---|---|---|---|
| `ttlBaseline` | 0.1 | **478,945,558** | Direct unjittered return baseline | Reference |
| `ttlJitter_compute` | 0.1 | **54,806,459** | Gaussian jitter per cache put (~18 ns overhead) | **OK** (SLO ≥ 10.0 M ops/s) |
| `ttlJitter_compute` | 0.2 | **52,697,903** | Configurable ratio variance sweep | **OK** |
| `ttlJitter_concurrent_uniformity` | 0.1 (8 threads) | **5,551,344** | Concurrent ThreadLocalRandom write distribution | **OK** |

---

### Benchmark 4 — Chain Pass-Through (`ChainPassThroughBenchmark`)
Measures ResiCache `ChainEngine` pass-through execution against direct method calls and Spring-native map lookup.

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
| `cost_1_handler_ttl` | 1 (TTL) | **31,466,289** | ~31.8 ns baseline |
| `cost_2_handlers` | 2 (TTL + Bloom) | **28,108,769** | +3.8 ns |
| `cost_3_handlers` | 3 (TTL + Bloom + Null) | **25,716,085** | +3.3 ns |
| `cost_4_handlers` | 4 (TTL + Bloom + Null + Sync) | **24,240,836** | +2.4 ns |
| `cost_5_handlers_full` | 5 (Full Depth Protection) | **22,488,609** | +3.2 ns |

*Conclusion: Each additional protection handler adds ~2.5–3.8 ns of chain advancement overhead in memory.*
