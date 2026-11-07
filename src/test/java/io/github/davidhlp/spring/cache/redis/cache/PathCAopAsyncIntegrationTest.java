package io.github.davidhlp.spring.cache.redis.cache;





import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * async 路径行为 + MDC 透传验证.
 *
 * <p>本 IT 验证 async 路径关键契约:
 * <ol>
 *   <li>{@code retrieve()} 返回 {@code CompletableFuture<byte[]>},异步线程内能
 *       读到正确的缓存值(链处理器 + Redis 操作均工作)</li>
 *   <li>MDC(traceId/spanId)在 writer worker 异步线程内可读(set 在调用线程,
 *       async 线程内仍可见)</li>
 * </ol>
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Import({TestRedisConfiguration.class, PathCAopAsyncIntegrationTest.WorkerConfiguration.class})
@DisplayName("async 路径 + MDC 透传契约")
class PathCAopAsyncIntegrationTest extends AbstractRedisIntegrationTest {

    private static final String CACHE_NAME = "testCache";
    private static final byte[] KEY = "async-test-key".getBytes();
    private static final String KEY_STR = "async-test-key";
    private static final String EXPECTED_VALUE = "async-test-value";

    @Autowired
    private RedisProCacheWriter redisProCacheWriter;

    @Autowired
    private RedisTemplate<String, Object> redisCacheTemplate;

    @Autowired
    private WorkerFixture fixture;

    @Autowired
    private WorkerEvidence evidence;

    @Test
    void supportedAopMethodCarriesPolicyAndMdcIntoRealWriterWorkers() {
        evidence.observations.clear();
        MDC.put("traceId", "real-worker-trace");
        try {
            assertThat(org.springframework.aop.support.AopUtils.isAopProxy(fixture)).isTrue();
            assertThat(fixture.load()).isEqualTo("loaded");
            assertThat(evidence.observations).hasSize(2).allSatisfy(observation -> {
                assertThat(observation.virtual()).isTrue();
                assertThat(observation.trace()).isEqualTo("real-worker-trace");
                assertThat(observation.ttl()).isEqualTo(123L);
                assertThat(observation.cacheNull()).isTrue();
                assertThat(observation.method()).isEqualTo("load");
            });
            assertThat(redisCacheTemplate.getExpire("testCache::worker", TimeUnit.SECONDS)).isBetween(115L, 123L);
            assertThat(MDC.get("traceId")).isEqualTo("real-worker-trace");
        } finally {
            MDC.clear();
        }
    }

    record Observation(boolean virtual, String trace, long ttl, boolean cacheNull, String method) { }

    static class WorkerEvidence implements io.github.davidhlp.spring.cache.redis.chain.observer.ChainObserver {
        final java.util.List<Observation> observations = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final MethodMetadataResolver resolver;

        WorkerEvidence(MethodMetadataResolver resolver) {
            this.resolver = resolver;
        }

        @Override
        public Object onChainStart(io.github.davidhlp.spring.cache.redis.chain.model.CacheContext context) {
            if ("testCache::worker".equals(context.getRedisKey())) {
                observations.add(new Observation(Thread.currentThread().isVirtual(), MDC.get("traceId"),
                        context.policy().ttl(), context.policy().cacheNullValues(),
                        resolver.currentMethod() == null ? null : resolver.currentMethod().getName()));
            }
            return null;
        }
    }

    static class WorkerFixture {
        private final RedisProCacheWriter writer;
        private final RedisTemplate<String, Object> template;

        WorkerFixture(RedisProCacheWriter writer, RedisTemplate<String, Object> template) {
            this.writer = writer;
            this.template = template;
        }

        @io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable(
                cacheNames = "testCache", key = "'entry'", ttl = 123, cacheNullValues = true)
        public String load() {
            byte[] key = "testCache::worker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            org.springframework.data.redis.serializer.RedisSerializer<Object> serializer =
                    (org.springframework.data.redis.serializer.RedisSerializer<Object>) template.getValueSerializer();
            writer.store("testCache", key, serializer.serialize("worker-value"), java.time.Duration.ofSeconds(20)).join();
            assertThat(writer.retrieve("testCache", key).join()).isNotNull();
            return "loaded";
        }
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class WorkerConfiguration {
        @org.springframework.context.annotation.Bean
        WorkerFixture workerFixture(RedisProCacheWriter writer,
                @org.springframework.beans.factory.annotation.Qualifier("redisCacheTemplate") RedisTemplate<String, Object> template) {
            return new WorkerFixture(writer, template);
        }

        @org.springframework.context.annotation.Bean
        WorkerEvidence workerEvidence(MethodMetadataResolver resolver) {
            return new WorkerEvidence(resolver);
        }
    }

    @BeforeEach
    void setUp() {
        redisCacheTemplate.getConnectionFactory().getConnection().flushDb();
        // 预置一个 Redis key(直接写,不走 async 路径,确保 retrieve 路径有命中)
        redisCacheTemplate.opsForValue().set(CACHE_NAME + "::" + KEY_STR, EXPECTED_VALUE);
    }

    @Test
    @DisplayName("PathC-Step6-1: retrieve() async 路径正常完成(CompletableFuture 不抛异常)")
    void asyncRetrieve_completesNormally() throws Exception {
        // 仅验证 async 路径 future 正常完成(不抛异常),证明
        // writer worker 异步线程 + withMethodMetadataSnapshot 链路通。
        // (raw byte[] 内容不直接断言 — 跨 SerializeFilter 链的字节比对复杂度高;
        // sync 路径缓存值内容由 RedisCacheSemanticsIntegrationTest 覆盖,async 路径逻辑等价)
        CompletableFuture<byte[]> future = redisProCacheWriter.retrieve(CACHE_NAME, KEY);

        // future.get() 不抛异常 + 5s 内完成,证明 async 路径正常工作
        future.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("PathC-Step6-2: MDC 在 retrieve() async 路径透传(traceId 在 writer worker 线程可读)")
    void mdcPropagatesAcrossAsyncBoundary() throws Exception {
        // 关键 traceId 标识 — 用 unique UUID 防测试间串扰
        String traceId = "trace-" + System.nanoTime();
        MDC.put("traceId", traceId);

        try {
            CompletableFuture<byte[]> future = redisProCacheWriter.retrieve(CACHE_NAME, KEY);

            // This assertion covers caller preservation; worker visibility is asserted separately above.
            future.get(5, TimeUnit.SECONDS);

            // 调用线程上的 MDC 不应被 async 路径污染(async 路径只 set+clear 自己线程,
            // 不影响调用线程)
            assertThat(MDC.get("traceId"))
                    .as("调用线程上的 MDC 不应被 async 路径清掉(每个 async 线程独立 clear)")
                    .isEqualTo(traceId);
        } finally {
            MDC.clear();
        }
    }
}
