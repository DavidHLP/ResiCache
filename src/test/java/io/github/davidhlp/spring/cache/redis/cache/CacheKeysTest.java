package io.github.davidhlp.spring.cache.redis.cache;




import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CacheKeys} 单元测试。
 *
 * <p>固化键派生不变量:bloom 键 ≡ actualKey;{@code fromRedisKey} 剥前缀;无前缀原样透传。
 * 防止 actualKey(剥前缀)与 createCacheKey(带前缀)之间的漂移在两个 bloom 消费者处复发。
 */
@DisplayName("CacheKeys 键派生 seam 测试")
class CacheKeysTest {

    @Test
    @DisplayName("fromRedisKey 剥 {cacheName}:: 前缀得 actualKey,redisKey/cacheName 原样保留")
    void fromRedisKey_stripsPrefix() {
        CacheKeys keys = CacheKeys.fromRedisKey("testCache", "testCache::user:1");

        assertThat(keys.actualKey()).isEqualTo("user:1");
        assertThat(keys.redisKey()).isEqualTo("testCache::user:1");
        assertThat(keys.cacheName()).isEqualTo("testCache");
    }

    @Test
    @DisplayName("bloomKey ≡ actualKey(单一真理源,杜绝链层与 loader 路径漂移)")
    void bloomKey_equalsActualKey() {
        CacheKeys keys = CacheKeys.fromRedisKey("testCache", "testCache::user:1");

        assertThat(keys.bloomKey()).isEqualTo(keys.actualKey()).isEqualTo("user:1");
    }

    @Test
    @DisplayName("redisKey 不含预期前缀时原样作为 actualKey(自定义 keyPrefix 兼容)")
    void fromRedisKey_noPrefix_passthrough() {
        CacheKeys keys = CacheKeys.fromRedisKey("testCache", "custom-prefix::user:1");

        assertThat(keys.actualKey()).isEqualTo("custom-prefix::user:1");
        assertThat(keys.bloomKey()).isEqualTo("custom-prefix::user:1");
    }
}
