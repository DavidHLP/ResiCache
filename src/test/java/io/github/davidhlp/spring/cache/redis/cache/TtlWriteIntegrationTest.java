package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Exercises TTL resolution, Spring conversion, serialization and Redis expiry together. */
class TtlWriteIntegrationTest extends AbstractRedisIntegrationTest {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private CacheValueCodec codec;

    @RedisCacheable(cacheNames = "ttl-boundary", ttl = Long.MAX_VALUE,
            randomTtl = true, variance = 0.5f)
    private String oversizedAnnotation(String key) {
        return key;
    }

    @ParameterizedTest
    @EnumSource(value = CacheOperation.class, names = {"PUT", "PUT_IF_ABSENT"})
    void roundedAndOversizedDurations_writeWithMatchingMetadataAndRedisExpiry(CacheOperation operation) {
        Duration[] inputs = {Duration.ofNanos(1), Duration.ofMillis(500), Duration.ofMillis(1500),
                Duration.ofSeconds(TtlPolicy.MAX_TTL_SECONDS - 1),
                Duration.ofSeconds(TtlPolicy.MAX_TTL_SECONDS, 1),
                Duration.ofSeconds(Long.MAX_VALUE / 1000),
                Duration.ofSeconds(Long.MAX_VALUE / 1000 + 1),
                Duration.ofSeconds(Long.MAX_VALUE, 999_999_999)};
        long cap = TtlPolicy.MAX_TTL_SECONDS;
        long[] expected = {1, 1, 2, cap - 1, cap, cap, cap, cap};
        for (int i = 0; i < inputs.length; i++) {
            assertWrite(operation, "duration:" + i, inputs[i], null, expected[i]);
        }
    }

    @ParameterizedTest
    @EnumSource(value = CacheOperation.class, names = {"PUT", "PUT_IF_ABSENT"})
    void oversizedJitteredAnnotation_writesAtBackendLimit(CacheOperation operation) throws Exception {
        Method method = getClass().getDeclaredMethod("oversizedAnnotation", String.class);
        RedisCacheable annotation = method.getAnnotation(RedisCacheable.class);
        RedisCacheableOperation policy = RedisCacheableOperation.fromAttributes(method, annotation.key(),
                new RedisCacheAttributesProjector().from(annotation));
        // With variance 0.5 every possible draw still exceeds the backend limit.
        assertWrite(operation, "annotation", Duration.ofSeconds(60), policy, TtlPolicy.MAX_TTL_SECONDS);
    }

    private void assertWrite(CacheOperation operation, String suffix, Duration ttl,
                             RedisCacheableOperation policy, long expectedSeconds) {
        String key = "ttl-boundary:" + operation + ":" + suffix;
        redisTemplate.delete(key);
        CacheErrorHandler errors = mock(CacheErrorHandler.class);
        ActualCacheHandler handler = new ActualCacheHandler(redisTemplate, redisTemplate.opsForValue(),
                codec, mock(RefreshCancellation.class), errors);
        CacheContext context = CacheContext.of(CacheInput.builder().operation(operation)
                .cacheName("ttl-boundary").redisKey(key).actualKey(suffix)
                .deserializedValue("value").ttl(ttl).cacheOperation(policy).build());
        new TtlHandler().doHandle(context, CacheResult::success);
        long before = System.nanoTime();
        try {
            assertThat(handler.doHandle(context, CacheResult::success).result().isSuccess()).isTrue();
            verifyNoInteractions(errors);
            CachedValue stored = (CachedValue) redisTemplate.opsForValue().get(key);
            assertThat(stored).isNotNull();
            assertThat(stored.getValue()).isEqualTo("value");
            assertThat(stored.getTtl()).isEqualTo(expectedSeconds);
            Long remaining = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
            assertThat(remaining).isNotNull().isPositive()
                    .isBetween(expectedSeconds * 1000 - elapsedMillis - 1, expectedSeconds * 1000);
            assertThat(stored.checkExpired()).isFalse();
        } finally {
            redisTemplate.delete(key);
        }
    }
}
