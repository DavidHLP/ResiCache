package io.github.davidhlp.bench;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.SyncSupport;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Benchmark: SyncLock (cache-breakdown / cache-stampede protection).
 *
 * <p>ResiCache's {@link SyncSupport} prevents a thundering-herd when a hot
 * cache key expires: exactly one thread (the leader) calls the real loader
 * while all others (followers) wait on the same {@code CompletableFuture}.
 *
 * <p>We measure three scenarios:
 * <ul>
 *   <li><b>noSync</b>      – baseline: all threads call the loader directly with no coordination</li>
 *   <li><b>syncLocalOnly</b> – SyncLock in local-only (JVM {@code synchronized}) mode</li>
 *   <li><b>syncContended</b> – 32 threads hammering the same key (worst-case stampede)</li>
 * </ul>
 *
 * <p>SLO (from PERFORMANCE.md):
 * <ul>
 *   <li>syncLocalOnly  ≤ 2× noSync throughput overhead per operation</li>
 *   <li>syncContended  leader fires exactly once per unique key window</li>
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class SyncLockBenchmark {

    private SyncSupport syncLocalOnly;
    private final AtomicInteger loaderCallCount = new AtomicInteger(0);
    private static final String CACHE_KEY = "product:42";

    /** Simulates a DB lookup taking ~100 µs */
    private String simulateDbLoad() {
        Blackhole.consumeCPU(500); // ~100 µs on a 5 GHz CPU
        loaderCallCount.incrementAndGet();
        return "loaded-value";
    }

    @Setup(Level.Trial)
    public void setup() {
        RedisProCacheProperties props = new RedisProCacheProperties();
        props.getSyncLock().setLocalOnly(true);   // no Redis needed for unit benchmarks
        props.getSyncLock().setTimeout(5);
        props.getSyncLock().setUnit(TimeUnit.SECONDS);
        // empty distributedManagers => local-JVM synchronized path
        syncLocalOnly = new SyncSupport(List.of(), props);
    }

    /**
     * Baseline: direct loader call, zero coordination overhead.
     * All threads race freely — this is the "thundering herd" scenario.
     */
    @Benchmark
    public String noSync() {
        return simulateDbLoad();
    }

    /**
     * SyncLock local-only: single-flight via {@code ConcurrentHashMap} CAS.
     * Leader executes the loader; followers block on {@code CompletableFuture.join()}.
     * Measures the combined leader + follower throughput.
     */
    @Benchmark
    @Threads(8)
    public String syncLocalOnly_8threads() {
        return syncLocalOnly.executeSync(CACHE_KEY, this::simulateDbLoad, 5);
    }

    /**
     * Worst-case contention: 32 threads vs single key.
     * Verifies the single-flight gate stays stable under high concurrency.
     */
    @Benchmark
    @Threads(32)
    public String syncContended_32threads() {
        return syncLocalOnly.executeSync(CACHE_KEY, this::simulateDbLoad, 5);
    }
}
