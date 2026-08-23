package io.github.davidhlp.bench;

import io.github.davidhlp.spring.cache.redis.protection.bloom.BloomFilterConfig;
import io.github.davidhlp.spring.cache.redis.protection.bloom.MessageDigestBloomHashStrategy;
import io.github.davidhlp.spring.cache.redis.protection.bloom.filter.LocalBloomIFilter;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Benchmark: Bloom-filter gate (cache-penetration protection).
 *
 * <p>ResiCache wraps every cache-miss path with a Bloom filter check so that
 * keys that were never stored (e.g. random IDs from a DoS scan) are rejected
 * before hitting the DB. This benchmark measures:
 *
 * <ul>
 *   <li><b>bloomMightContain_hit</b>  – fast path: key IS in the filter (true positive)</li>
 *   <li><b>bloomMightContain_miss</b> – key is NOT in filter (definitive negative); loader skipped</li>
 *   <li><b>bloomPut</b>               – insertion cost of a new key into the filter</li>
 * </ul>
 *
 * <p>SLO (from PERFORMANCE.md):
 * <ul>
 *   <li>bloomMightContain_hit  ≥ 5 M ops/s on a single thread (bit-array read only)</li>
 *   <li>bloomPut throughput    ≥ 1 M ops/s  (hash + bit-set)</li>
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class BloomFilterBenchmark {

    private LocalBloomIFilter bloom;
    private static final String CACHE_NAME = "users";
    private static final String KNOWN_KEY   = "user:1001";
    private static final String UNKNOWN_KEY = "user:GHOST_99999";

    @Param({"8388608"})
    public int bitSize;

    @Param({"3"})
    public int hashFunctions;

    @Setup(Level.Trial)
    public void setup() {
        BloomFilterConfig config = new BloomFilterConfig("bf:", bitSize, hashFunctions, 10000);
        bloom = new LocalBloomIFilter(config, new MessageDigestBloomHashStrategy());
        // Pre-populate so mightContain hits are realistic
        for (int i = 0; i < 50000; i++) {
            bloom.add(CACHE_NAME, "user:" + i);
        }
        bloom.add(CACHE_NAME, KNOWN_KEY);
    }

    /**
     * True-positive mightContain: key was inserted, filter returns {@code true}.
     * This is the hot path for every valid cache-miss that goes to the DB.
     */
    @Benchmark
    public boolean bloomMightContain_hit() {
        return bloom.mightContain(CACHE_NAME, KNOWN_KEY);
    }

    /**
     * Definitive-negative mightContain: key was never inserted.
     * Filter returns {@code false} and the cache handler short-circuits without
     * hitting the DB — exactly the penetration-protection behaviour.
     */
    @Benchmark
    public boolean bloomMightContain_miss() {
        return bloom.mightContain(CACHE_NAME, UNKNOWN_KEY);
    }

    /**
     * Insertion cost: adding a new key into the in-JVM bit-array filter.
     * Called once per first-time DB load to register the key as "known".
     */
    @Benchmark
    public void bloomPut(Blackhole bh) {
        // rotate through keys so we don't always re-insert the same slot
        String key = "bench:key:" + (System.nanoTime() & 0xFFFFL);
        bloom.add(CACHE_NAME, key);
        bh.consume(key);
    }
}
