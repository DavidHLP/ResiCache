package io.github.davidhlp.spring.cache.redis.cache;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Benchmark: TTL-jitter policy (cache-avalanche protection).
 *
 * <p>When many cache entries are written with the same TTL they expire in a
 * burst, causing a mass DB stampede (cache avalanche). ResiCache's
 * {@link TtlPolicy} adds a configurable random jitter to each entry's
 * TTL so expirations are spread evenly over time.
 *
 * <p>We measure:
 * <ul>
 *   <li><b>ttlJitter_compute</b>   – cost of computing a jittered TTL per put operation</li>
 *   <li><b>ttlJitter_concurrentThroughput</b> – 8-thread computation throughput; distribution is verified by unit tests</li>
 *   <li><b>ttlBaseline</b>         – raw {@code calculateFinalTtl} with no jitter (reference)</li>
 * </ul>
 *
 * <p>Historical measurements are recorded in PERFORMANCE.md. This suite has no
 * enforced throughput SLO; correctness is verified by focused contract tests.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TtlJitterBenchmark {

    private long baseTtlSeconds;

    /**
     * Jitter ratio to apply – parameterised so CI can sweep values.
     * 0.2 means ±20 % random spread around base TTL.
     */
    @Param({"0.1", "0.2"})
    public float jitterRatio;

    @Setup(Level.Trial)
    public void setup() {
        baseTtlSeconds = 600L;
    }

    /**
     * Main benchmark: computing a jittered TTL per cache put.
     * Called once for every entry stored in Redis – must be cheap.
     */
    @Benchmark
    public long ttlJitter_compute() {
        return TtlPolicy.calculateFinalTtl(baseTtlSeconds, true, jitterRatio);
    }

    /**
     * Reference baseline: no jitter, just direct return.
     * Isolates the overhead introduced by the jitter calculation alone.
     */
    @Benchmark
    public long ttlBaseline() {
        return TtlPolicy.calculateFinalTtl(baseTtlSeconds, false, 0.0f);
    }

    /** Measures computation throughput with 8 threads; does not assert distribution. */
    @Benchmark
    @Threads(8)
    public void ttlJitter_concurrentThroughput(Blackhole bh) {
        long jittered = TtlPolicy.calculateFinalTtl(baseTtlSeconds, true, jitterRatio);
        bh.consume(jittered);
    }
}
