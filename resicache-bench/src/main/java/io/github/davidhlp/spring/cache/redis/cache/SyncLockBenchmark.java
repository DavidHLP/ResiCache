package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

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
 *   <li><b>syncLocalOnly</b> – SyncLock in explicit local-only coordination mode</li>
 *   <li><b>syncContended</b> – 32 threads hammering the same key (worst-case stampede)</li>
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
public class SyncLockBenchmark {

    private SyncSupport syncLocalOnly;
    private static final String CACHE_KEY = "product:42";

    /** CPU-bound loader substitute; duration depends on the measured machine. */
    private String simulateDbLoad() {
        Blackhole.consumeCPU(500);
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
     * Measures combined leader/follower throughput; unit tests verify the single-flight protocol.
     */
    @Benchmark
    @Threads(32)
    public String syncContended_32threads() {
        return syncLocalOnly.executeSync(CACHE_KEY, this::simulateDbLoad, 5);
    }
}
