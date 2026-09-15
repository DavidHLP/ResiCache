package io.github.davidhlp.spring.cache.redis.cache;




import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 统一 read-through load 协议(ADR-0001 §13)在 cache 入口的契约测试 —— 无容器。
 *
 * <p>两条 loader 路径(sync / 非 sync)共用 {@code LoaderOrchestrator} 内的同一协议,
 * 因此非 sync 路径也必须:走带 metrics 的 {@code put} 写回、缓存命中不调 loader、
 * 写回失败不覆盖 loader 值。本测试用内存 writer 驱动真实 {@link RedisProCache}。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisProCache 统一 load 协议")
class RedisProCacheLoadPathTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private CacheStatisticsCollector statistics;

    @Mock
    private TypeSupport typeSupport;

    @Mock
    private CacheHandlerChainFactory chainFactory;

    @Mock
    private CacheHandlerChain chain;

    private static final String CACHE_NAME = "load-path-cache";
    private static final String SENTINEL_KEY = "sentinel-key:user#42";

    /** 内存 writer:读回固定字节(或 miss),写路径可注入失败。 */
    private static final class MemoryWriter implements RedisCacheWriter {

        private final byte[] stored;
        private final boolean failPut;
        private final AtomicInteger putCalls = new AtomicInteger();

        MemoryWriter(byte[] stored, boolean failPut) {
            this.stored = stored;
            this.failPut = failPut;
        }

        @Override public byte[] get(String name, byte[] key) { return stored; }

        @Override public byte[] get(String name, byte[] key, Duration ttl) { return stored; }

        @Override public CompletableFuture<byte[]> retrieve(String name, byte[] key, Duration ttl) {
            return CompletableFuture.completedFuture(stored);
        }

        @Override public void put(String name, byte[] key, byte[] value, Duration ttl) {
            putCalls.incrementAndGet();
            if (failPut) {
                throw new CacheOperationException(
                        CacheOperation.PUT,
                        io.github.davidhlp.spring.cache.redis.chain.CacheResult.FailureKind.REDIS,
                        name,
                        new IllegalStateException("redis put failed for key "
                                + new String(key, StandardCharsets.UTF_8)));
            }
        }

        @Override public CompletableFuture<Void> store(String name, byte[] key, byte[] value, Duration ttl) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public byte[] putIfAbsent(String name, byte[] key, byte[] value, Duration ttl) {
            return null;
        }

        @Override public RedisCacheWriter withStatisticsCollector(
                org.springframework.data.redis.cache.CacheStatisticsCollector collector) {
            return this;
        }

        @Override public org.springframework.data.redis.cache.CacheStatistics getCacheStatistics(String name) {
            return new org.springframework.data.redis.cache.CacheStatistics() {
                @Override public String getCacheName() { return name; }
                @Override public long getPuts() { return putCalls.get(); }
                @Override public long getGets() { return 0; }
                @Override public long getHits() { return 0; }
                @Override public long getMisses() { return 0; }
                @Override public long getDeletes() { return 0; }
                @Override public long getLockWaitDuration(java.util.concurrent.TimeUnit unit) { return 0; }
                @Override public java.time.Instant getSince() { return java.time.Instant.EPOCH; }
                @Override public java.time.Instant getLastReset() { return java.time.Instant.EPOCH; }
            };
        }

        @Override public void evict(String name, byte[] key) { }

        @Override public void clear(String name, byte[] pattern) { }

        @Override public void clearStatistics(String name) { }
    }

    private RedisProCache cacheWith(RedisCacheWriter writer) {
        return new RedisProCache(CACHE_NAME, writer,
                RedisCacheConfiguration.defaultCacheConfig(),
                ResiCacheFeatures.builder().meterRegistry(new SimpleMeterRegistry()).build());
    }
    private RedisProCacheWriter writerWithPutFailure() {
        when(chainFactory.createChain()).thenReturn(chain);
        when(typeSupport.bytesToString(any())).thenAnswer(invocation ->
                new String(invocation.getArgument(0), StandardCharsets.UTF_8));
        when(chain.execute(any(CacheContext.class))).thenAnswer(invocation -> {
            CacheContext context = invocation.getArgument(0);
            if (context.getOperation() == CacheOperation.GET) {
                return CacheResult.miss();
            }
            return CacheResult.failure(
                    CacheOperation.PUT,
                    CacheResult.FailureKind.REDIS,
                    new IllegalStateException("redis put failed for key " + SENTINEL_KEY));
        });
        return new RedisProCacheWriter(
                redisTemplate,
                valueOperations,
                statistics,
                typeSupport,
                chainFactory,
                null);
    }

    @Test
    @DisplayName("非 sync loader 写回走带 metrics 的 put(与 sync 路径同记账)")
    void nonSyncWriteBack_recordsPutMetrics() {
        MemoryWriter writer = new MemoryWriter(null, false);
        RedisProCache cache = cacheWith(writer);

        String value = cache.get(SENTINEL_KEY, () -> "business-value");

        assertThat(value).isEqualTo("business-value");
        assertThat(writer.putCalls.get()).as("写回恰好一次").isEqualTo(1);
        assertThat(cache.metrics().putCount())
                .as("非 sync 写回必须计入 put metrics(统一协议前该路径不计)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("缓存命中 → loader 不被调用,且不写回")
    void cacheHit_skipsLoaderAndWriteBack() {
        RedisSerializer<Object> serializer = new JdkSerializationRedisSerializer();
        MemoryWriter writer = new MemoryWriter(serializer.serialize("cached-value"), false);
        RedisProCache cache = cacheWith(writer);
        AtomicBoolean loaderCalled = new AtomicBoolean(false);

        Object value = cache.get(SENTINEL_KEY, () -> {
            loaderCalled.set(true);
            return "fresh-value";
        });

        assertThat(value).isEqualTo("cached-value");
        assertThat(loaderCalled).as("命中不得回源").isFalse();
        assertThat(writer.putCalls.get()).isZero();
        assertThat(cache.metrics().putCount()).isZero();
    }

    @Test
    @DisplayName("非 sync 写回失败 → 仍返回 loader 值,且 WARN 不含 raw key")
    void nonSyncWriteBackFailure_returnsLoaderValueWithRedactedWarning() {
        MemoryWriter writer = new MemoryWriter(null, true);
        RedisProCache cache = cacheWith(writer);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        Logger logger = (Logger) LoggerFactory.getLogger(LoaderOrchestrator.class);
        logger.addAppender(captured);

        try {
            String value = cache.get(SENTINEL_KEY, () -> "business-value");

            assertThat(value)
                    .as("availability-first:写回失败不得覆盖已加载值(ADR-02)")
                    .isEqualTo("business-value");

            String warnText = captured.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertThat(warnText)
                    .as("写回失败必须可观测")
                    .contains("write-back failed")
                    .as("ADR-0001 §15:WARN 不带 raw key")
                    .doesNotContain(SENTINEL_KEY);
        } finally {
            logger.detachAppender(captured);
        }
    }
    @Test
    @DisplayName("cache 与 native writer 共用写回容错和单点 WARN")
    void cacheAndWriter_shareWriteBackToleranceAndWarning() {
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        Logger logger = (Logger) LoggerFactory.getLogger(LoaderOrchestrator.class);
        logger.addAppender(captured);

        try {
            RedisProCache cache = cacheWith(new MemoryWriter(null, true));
            String cacheValue = cache.get(SENTINEL_KEY, () -> "business-value");

            RedisProCacheWriter writer = writerWithPutFailure();
            byte[] writerValue = writer.get(
                    CACHE_NAME,
                    SENTINEL_KEY.getBytes(StandardCharsets.UTF_8),
                    () -> "business-value".getBytes(StandardCharsets.UTF_8),
                    null,
                    false);

            assertThat(cacheValue).isEqualTo("business-value");
            assertThat(new String(writerValue, StandardCharsets.UTF_8))
                    .isEqualTo("business-value");

            List<String> warnings = captured.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertThat(warnings).hasSize(2);
            assertThat(warnings.get(0)).isEqualTo(warnings.get(1));
            assertThat(warnings.get(0))
                    .contains("Cache write-back failed after successful load")
                    .doesNotContain(SENTINEL_KEY);
        } finally {
            logger.detachAppender(captured);
        }
    }
}
