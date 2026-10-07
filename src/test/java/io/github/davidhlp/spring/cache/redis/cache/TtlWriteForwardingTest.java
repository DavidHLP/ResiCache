package io.github.davidhlp.spring.cache.redis.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TtlWriteForwardingTest {

    @ParameterizedTest
    @EnumSource(value = CacheOperation.class, names = {"PUT", "PUT_IF_ABSENT"})
    @SuppressWarnings("unchecked")
    void forwardsRoundedPositiveDurationToRedis(CacheOperation operation) {
        Duration[] inputs = {Duration.ofNanos(1), Duration.ofMillis(500), Duration.ofSeconds(1),
                Duration.ofMillis(1500), Duration.ofSeconds(Long.MAX_VALUE),
                Duration.ofSeconds(Long.MAX_VALUE, 1)};
        long[] expected = {1, 1, 1, 2, Long.MAX_VALUE, Long.MAX_VALUE};
        for (int i = 0; i < inputs.length; i++) {
            ValueOperations<String, Object> values = mock(ValueOperations.class);
            if (operation == CacheOperation.PUT_IF_ABSENT) {
                when(values.setIfAbsent(eq("cache:key"), any(), any(Duration.class))).thenReturn(true);
            }
            ActualCacheHandler storage = new ActualCacheHandler(mock(RedisTemplate.class), values,
                    new CacheValueCodec(new ObjectMapper()), mock(RefreshCancellation.class),
                    mock(CacheErrorHandler.class));
            CacheContext context = CacheContext.of(CacheInput.builder().operation(operation)
                    .cacheName("cache").redisKey("cache:key").actualKey("key")
                    .deserializedValue("value").ttl(inputs[i]).build());
            new TtlHandler().doHandle(context, CacheResult::success);
            storage.doHandle(context, CacheResult::success);
            org.mockito.ArgumentCaptor<Object> stored = org.mockito.ArgumentCaptor.forClass(Object.class);
            if (operation == CacheOperation.PUT) {
                verify(values).set(eq("cache:key"), stored.capture(), eq(Duration.ofSeconds(expected[i])));
            } else {
                verify(values).setIfAbsent(eq("cache:key"), stored.capture(), eq(Duration.ofSeconds(expected[i])));
            }
            assertThat(stored.getValue()).isInstanceOf(CachedValue.class);
            assertThat(((CachedValue) stored.getValue()).getTtl()).isEqualTo(expected[i]);
        }
    }
}
