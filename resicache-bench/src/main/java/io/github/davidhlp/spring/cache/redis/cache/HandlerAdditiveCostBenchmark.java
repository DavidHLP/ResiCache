package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.CacheHandler;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.HandlerResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark: Additive execution cost per handler in the responsibility chain.
 *
 * <p>Measures the marginal latency introduced by evaluating 1, 2, 3, 4, and 5
 * no-op handlers in the {@link ChainEngine}; no protection policy or Redis I/O is measured.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class HandlerAdditiveCostBenchmark {

    private ChainEngine engine;
    private CacheContext sampleContext;

    private List<CacheHandler> chain1;
    private List<CacheHandler> chain2;
    private List<CacheHandler> chain3;
    private List<CacheHandler> chain4;
    private List<CacheHandler> chain5;

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
                .redisKey("order:1001")
                .actualKey("order:1001")
                .build());

        PassthroughHandler p0 = new PassthroughHandler();
        PassthroughHandler p1 = new PassthroughHandler();
        PassthroughHandler p2 = new PassthroughHandler();
        PassthroughHandler p3 = new PassthroughHandler();
        PassthroughHandler p4 = new PassthroughHandler();

        chain1 = List.of(p0);
        chain2 = List.of(p0, p1);
        chain3 = List.of(p0, p1, p2);
        chain4 = List.of(p0, p1, p2, p3);
        chain5 = List.of(p0, p1, p2, p3, p4);
    }

    @Benchmark
    public void cost_1_passthrough(Blackhole bh) {
        bh.consume(engine.execute(chain1, sampleContext));
    }

    @Benchmark
    public void cost_2_passthrough(Blackhole bh) {
        bh.consume(engine.execute(chain2, sampleContext));
    }

    @Benchmark
    public void cost_3_passthrough(Blackhole bh) {
        bh.consume(engine.execute(chain3, sampleContext));
    }

    @Benchmark
    public void cost_4_passthrough(Blackhole bh) {
        bh.consume(engine.execute(chain4, sampleContext));
    }

    @Benchmark
    public void cost_5_passthrough(Blackhole bh) {
        bh.consume(engine.execute(chain5, sampleContext));
    }
}
