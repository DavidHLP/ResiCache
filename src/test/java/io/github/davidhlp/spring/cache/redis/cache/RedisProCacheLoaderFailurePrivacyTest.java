package io.github.davidhlp.spring.cache.redis.cache;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * RM-008 loader 失败 key 隐私单元测试(无容器)。
 *
 * <p>契约:loader 失败仍以异常呈现(不吞、不降级为 miss),但公开异常文本
 * 不得包含 raw key —— checked 包装只携带低基数 cacheName;Spring 构造的
 * {@link Cache.ValueRetrievalException}(message 内嵌 raw key)被重建为
 * key=cacheName 的同型异常(保留原始 cause;loader 参数置 null,Spring 7
 * 不存储 loader)。
 */
@DisplayName("RM-008 loader 失败 raw key 隐私")
class RedisProCacheLoaderFailurePrivacyTest {

    private static final String SENTINEL_KEY = "sentinel-key:user#42";
    private static final String CACHE_NAME = "unit-cache";

    /** 内存 miss writer:get 恒 null(未命中),写路径 no-op — 不触碰 Redis。 */
    private static RedisCacheWriter missWriter() {
        return new RedisCacheWriter() {
            @Override public byte[] get(String name, byte[] key) { return null; }
            @Override public CompletableFuture<byte[]> retrieve(String name, byte[] key, Duration ttl) {
                return CompletableFuture.completedFuture(null);
            }
            @Override public void put(String name, byte[] key, byte[] value, Duration ttl) { }
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
                    @Override public long getPuts() { return 0; }
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
        };
    }

    private RedisProCache newCache() {
        return new RedisProCache(CACHE_NAME, missWriter(),
                RedisCacheConfiguration.defaultCacheConfig(), ResiCacheFeatures.none());
    }

    @Test
    @DisplayName("checked loader 失败包装:message 含 cacheName、不含 raw key,cause 保留")
    void checkedWrapper_carriesCacheName_notRawKey() {
        RedisProCache cache = newCache();

        RuntimeException translated = cache.translateFailure(
                new IOException("boom"), cache.getName());

        assertThat(translated.getMessage())
                .contains(CACHE_NAME)
                .doesNotContain(SENTINEL_KEY);
        assertThat(translated.getCause()).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("Spring VRE 重建:类型/cause 保留,key 位替换为 cacheName,message 不含 raw key")
    void valueRetrievalException_rebuiltWithoutRawKey() {
        RedisProCache cache = newCache();
        Cache.ValueRetrievalException original = new Cache.ValueRetrievalException(
                SENTINEL_KEY, () -> "v", new IOException("boom"));

        RuntimeException translated = cache.translateFailure(original, cache.getName());

        assertThat(translated)
                .isInstanceOf(Cache.ValueRetrievalException.class)
                .isNotSameAs(original);
        assertThat(translated.getMessage())
                .contains(CACHE_NAME)
                .doesNotContain(SENTINEL_KEY);
        assertThat(translated.getCause()).isInstanceOf(IOException.class);
        // key 位被替换为低基数 cacheName:getKey() 不泄露 raw key
        assertThat(((Cache.ValueRetrievalException) translated).getKey())
                .isEqualTo(CACHE_NAME);
    }

    @Test
    @DisplayName("其他 RuntimeException 原样透传(保留原始栈,不二次包装)")
    void plainRuntimeException_passthrough() {
        RedisProCache cache = newCache();
        IllegalStateException original = new IllegalStateException("internal");

        RuntimeException translated = cache.translateFailure(original, cache.getName());

        assertThat(translated).isSameAs(original);
    }

    @Test
    @DisplayName("路径级:default loader 路径 checked 失败 → 异常 message 无 raw key")
    void endToEnd_defaultPath_checkedLoaderFailure_noRawKey() {
        // 内存 miss writer 走真实 get(key, loader) 路径:
        // writer miss → loader 抛 checked → Spring 包装为 VRE(key=raw key)
        // → translateFailure 重建 → 公开 message 不含 sentinel。
        RedisProCache cache = newCache();

        assertThatThrownBy(() -> cache.get(SENTINEL_KEY, () -> {
            throw new IOException("boom");
        }))
                .isInstanceOf(Cache.ValueRetrievalException.class)
                .hasMessageContaining(CACHE_NAME)
                .hasMessageNotContaining(SENTINEL_KEY)
                .hasCauseInstanceOf(IOException.class);
    }
}
