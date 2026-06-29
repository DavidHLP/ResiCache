package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.AbstractRedisIntegrationTest;
import io.github.davidhlp.spring.cache.redis.TestApplication;
import io.github.davidhlp.spring.cache.redis.TestRedisConfiguration;
import io.github.davidhlp.spring.cache.redis.chain.MethodMetadataResolver;
import io.github.davidhlp.spring.cache.redis.integration.TestCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WS-1.5 JMH 基准最小切片 — smoke test 级别.
 *
 * <p>分歧推荐表约束: <strong>不引入 jmh 框架</strong>(per 「引入新依赖/框架 → 不引入」)。
 * JMH 是 jmh-core 间接依赖,引入价值需评估(对 solo 维护者不构成阻塞 v0.1.0 发版)。
 *
 * <p>本 tick 用 JUnit + {@link System#nanoTime()} 跑简单循环测:
 * <ol>
 *   <li>cache hit 延迟(预置 value → 调 get → 测 N 次)</li>
 *   <li>cache miss 延迟(value 不存在 → 调 get → 测 N 次)</li>
 *   <li>async 路径延迟(测 retrieve() future.get() 端到端)</li>
 * </ol>
 *
 * <p>输出 mean / p50 / p99 到 stdout,assert 一些宽松上限(保证 smoke test
 * 通过 — 不严格 bound,避免 CI 抖动)。本测试不是真正 JMH 基准,只是
 * 防止性能意外回归(平均延迟 > 100ms 触发失败)。
 *
 * <p>v0.1.0 发版后,若用户/社区有需求再考虑引入 jmh 框架做正式基准
 * (Toxiproxy + JMH 是 v0.2.0 路线图)。
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Import(TestRedisConfiguration.class)
@DisplayName("WS-1.5 — cache 延迟 smoke 基准(不引入 jmh)")
class CacheLatencySmokeBenchmarkTest extends AbstractRedisIntegrationTest {

    private static final int WARMUP_ITERATIONS = 50;
    private static final int MEASURE_ITERATIONS = 1000;
    private static final long MAX_MEAN_NANOS = TimeUnit.MILLISECONDS.toNanos(100);  // 100ms 上限(smoke test,不是严格)

    @Autowired
    private TestCacheService cacheService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RedisProCacheWriter writer;

    @Autowired
    private MethodMetadataResolver methodMetadataResolver;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().flushDb();
    }

    @Test
    @DisplayName("WS-1.5-Smoke-1: cache hit 平均延迟 < 100ms(1000 次迭代)")
    void cacheHit_latencyUnderThreshold() {
        // 预置 value 让后续 get 都命中
        cacheService.getById(1L);  // miss + put
        cacheService.reset();      // 重置 call count 不影响,但 cache 中已有 value

        // Warmup(JVM JIT warmup)
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            cacheService.getById(1L);
        }

        // Measure
        long[] latencies = new long[MEASURE_ITERATIONS];
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            long start = System.nanoTime();
            cacheService.getById(1L);
            latencies[i] = System.nanoTime() - start;
        }

        // Stats
        long mean = mean(latencies);
        long p50 = percentile(latencies, 50);
        long p99 = percentile(latencies, 99);

        System.out.printf("[WS-1.5 cache hit] mean=%.2f us, p50=%.2f us, p99=%.2f us, n=%d%n",
                mean / 1000.0, p50 / 1000.0, p99 / 1000.0, MEASURE_ITERATIONS);

        assertThat(mean)
                .as("cache hit mean latency 应 < 100ms(性能回归 smoke 门槛;JMH 正式基准留 v0.2.0)")
                .isLessThan(MAX_MEAN_NANOS);
    }

    @Test
    @DisplayName("WS-1.5-Smoke-2: cache miss 平均延迟 < 100ms(1000 次迭代,每次 unique key)")
    void cacheMiss_latencyUnderThreshold() {
        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            cacheService.getByIdWithPureSpring((long) i);
        }

        // Measure(每次 unique key → cache miss)
        long[] latencies = new long[MEASURE_ITERATIONS];
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            long start = System.nanoTime();
            cacheService.getByIdWithPureSpring((long) (i + WARMUP_ITERATIONS));
            latencies[i] = System.nanoTime() - start;
        }

        long mean = mean(latencies);
        long p50 = percentile(latencies, 50);
        long p99 = percentile(latencies, 99);

        System.out.printf("[WS-1.5 cache miss] mean=%.2f us, p50=%.2f us, p99=%.2f us, n=%d%n",
                mean / 1000.0, p50 / 1000.0, p99 / 1000.0, MEASURE_ITERATIONS);

        assertThat(mean)
                .as("cache miss mean latency 应 < 100ms(smoke 门槛)")
                .isLessThan(MAX_MEAN_NANOS);
    }

    @Test
    @DisplayName("WS-1.5-Smoke-3: async retrieve 平均延迟 < 100ms(1000 次迭代)")
    void asyncRetrieve_latencyUnderThreshold() throws Exception {
        // 预置 value
        cacheService.getById(1L);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            writer.retrieve("testCache", ("async-bench-" + i).getBytes()).get();
        }

        // Measure
        long[] latencies = new long[MEASURE_ITERATIONS];
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            byte[] key = ("async-bench-" + (i + WARMUP_ITERATIONS)).getBytes();
            long start = System.nanoTime();
            writer.retrieve("testCache", key).get();
            latencies[i] = System.nanoTime() - start;
        }

        long mean = mean(latencies);
        long p50 = percentile(latencies, 50);
        long p99 = percentile(latencies, 99);

        System.out.printf("[WS-1.5 async retrieve] mean=%.2f us, p50=%.2f us, p99=%.2f us, n=%d%n",
                mean / 1000.0, p50 / 1000.0, p99 / 1000.0, MEASURE_ITERATIONS);

        assertThat(mean)
                .as("async retrieve mean latency 应 < 100ms(smoke 门槛)")
                .isLessThan(MAX_MEAN_NANOS);
    }

    private static long mean(long[] values) {
        long sum = 0;
        for (long v : values) sum += v;
        return sum / values.length;
    }

    private static long percentile(long[] values, int p) {
        long[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }
}
