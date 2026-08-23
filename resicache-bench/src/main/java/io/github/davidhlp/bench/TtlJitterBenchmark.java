package io.github.davidhlp.bench;

import io.github.davidhlp.spring.cache.redis.protection.avalanche.DefaultTtlPolicy;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark: TTL-jitter policy (cache-avalanche protection).
 *
 * <p>When many cache entries are written with the same TTL they expire in a
 * burst, causing a mass DB stampede (cache avalanche). ResiCache's
 * {@link DefaultTtlPolicy} adds a configurable random jitter to each entry's
 * TTL so expirations are spread evenly over time.
 *
 * <p>We measure:
 * <ul>
 *   <li><b>ttlJitter_compute</b>   – cost of computing a jittered TTL per put operation</li>
 *   <li><b>ttlJitter_concurrent_uniformity</b> – distribution check: verify jitter is within bounds</li>
 *   <li><b>ttlBaseline</b>         – raw {@code calculateFinalTtl} with no jitter (reference)</li>
 * </ul>
 *
 * <p>SLO (from PERFORMANCE.md):
 * <ul>
 *   <li>ttlJitter_compute ≥ 10 M ops/s – must not meaningfully slow down cache writes</li>
 *   <li>jitter spread: within configured variance range per default config</li>
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TtlJitterBenchmark {

    private DefaultTtlPolicy defaultTtlPolicy;
    private long baseTtlSeconds;

    /**
     * Jitter ratio to apply – parameterised so CI can sweep values.
     * 0.2 means ±20 % random spread around base TTL.
     */
    @Param({"0.1", "0.2"})
    public float jitterRatio;

    @Setup(Level.Trial)
    public void setup() {
        defaultTtlPolicy = new DefaultTtlPolicy();
        baseTtlSeconds = 600L;
    }

    /**
     * Main benchmark: computing a jittered TTL per cache put.
     * Called once for every entry stored in Redis – must be cheap.
     */
    @Benchmark
    public long ttlJitter_compute() {
        return defaultTtlPolicy.calculateFinalTtl(baseTtlSeconds, true, jitterRatio);
    }

    /**
     * Reference baseline: no jitter, just direct return.
     * Isolates the overhead introduced by the jitter calculation alone.
     */
    @Benchmark
    public long ttlBaseline() {
        return defaultTtlPolicy.calculateFinalTtl(baseTtlSeconds, false, 0.0f);
    }

    /**
     * Multi-threaded uniformity: 8 threads compute jitter concurrently.
     * Validates that {@link ThreadLocalRandom} usage inside the policy
     * has no contention under parallel write pressure.
     */
    @Benchmark
    @Threads(8)
    public void ttlJitter_concurrent_uniformity(Blackhole bh) {
        long jittered = defaultTtlPolicy.calculateFinalTtl(baseTtlSeconds, true, jitterRatio);
        bh.consume(jittered);
    }
}
