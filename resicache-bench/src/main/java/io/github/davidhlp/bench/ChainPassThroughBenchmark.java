package io.github.davidhlp.bench;

import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.ChainEngine;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheInput;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark: Chain Pass-Through vs Spring-native caching baseline.
 *
 * <p>Measures the baseline execution overhead of ResiCache's {@link ChainEngine}
 * pass-through against direct method execution and native in-memory cache lookup.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ChainPassThroughBenchmark {

    private ChainEngine engine;
    private CacheContext sampleContext;
    private List<CacheHandler> passthroughChain;
    private Map<String, Object> springNativeMockCache;
    private static final String KEY = "order:1001";
    private static final String VALUE = "order-payload-data";

    private static class PassthroughHandler implements CacheHandler {
        @Override
        public HandlerResult handle(CacheContext context) {
            return HandlerResult.continueChain();
        }
    }

    @Setup(Level.Trial)
    public void setup() {
        engine = new ChainEngine();
        sampleContext = CacheContext.of(CacheInput.builder()
                .operation(CacheOperation.GET)
                .cacheName("orders")
                .redisKey(KEY)
                .actualKey(KEY)
                .build());
        passthroughChain = List.of(new PassthroughHandler());
        springNativeMockCache = new ConcurrentHashMap<>();
        springNativeMockCache.put(KEY, VALUE);
    }

    /**
     * Baseline 1: Direct method invocation (zero caching overhead).
     */
    @Benchmark
    public Object directInvocation() {
        return VALUE;
    }

    /**
     * Baseline 2: Spring-native concurrent map cache lookup.
     */
    @Benchmark
    public Object springNativeCacheLookup() {
        return springNativeMockCache.get(KEY);
    }

    /**
     * ResiCache: ChainEngine pass-through overhead (1 no-op passthrough handler).
     */
    @Benchmark
    public void chainPassThrough(Blackhole bh) {
        bh.consume(engine.execute(passthroughChain, sampleContext));
    }
}
