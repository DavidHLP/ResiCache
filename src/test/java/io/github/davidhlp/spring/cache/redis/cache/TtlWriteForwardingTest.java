package io.github.davidhlp.spring.cache.redis.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.model.CacheContext;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.types.Expiration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TtlWriteForwardingTest {

    @ParameterizedTest
    @EnumSource(value = CacheOperation.class, names = {"PUT", "PUT_IF_ABSENT"})
    @SuppressWarnings("unchecked")
    void forwardsRoundedPositiveDurationToRedis(CacheOperation operation) {
        Duration[] inputs = {Duration.ofNanos(1), Duration.ofMillis(500), Duration.ofSeconds(1),
                Duration.ofMillis(1500), Duration.ofSeconds(Long.MAX_VALUE / 1000),
                Duration.ofSeconds(Long.MAX_VALUE / 1000 + 1), Duration.ofSeconds(Long.MAX_VALUE),
                Duration.ofSeconds(Long.MAX_VALUE, 1)};
        long cap = Long.MAX_VALUE / 2000;
        long[] expected = {1, 1, 1, 2, cap, cap, cap, cap};
        for (int i = 0; i < inputs.length; i++) {
            // Execute Spring's real Duration conversion; mock only the terminal Expiration overload.
            // SDR 4.1 no longer delegates Duration through the deprecated long/TimeUnit overload.
            ValueOperations<String, Object> values = mock(ValueOperations.class, CALLS_REAL_METHODS);
            if (operation == CacheOperation.PUT_IF_ABSENT) {
                doReturn(true).when(values).setIfAbsent(eq("cache:key"), any(), any(Expiration.class));
            }
            ActualCacheHandler storage = new ActualCacheHandler(mock(RedisTemplate.class), values,
                    new CacheValueCodec(new ObjectMapper()), mock(RefreshCancellation.class),
                    mock(CacheErrorHandler.class));
            CacheContext context = CacheContext.of(CacheInput.builder().operation(operation)
                    .cacheName("cache").redisKey("cache:key").actualKey("key")
                    .deserializedValue("value").ttl(inputs[i]).build());
            new TtlHandler().doHandle(context, CacheResult::success);
            assertThat(storage.doHandle(context, CacheResult::success).result().isSuccess()).isTrue();
            org.mockito.ArgumentCaptor<Object> stored = org.mockito.ArgumentCaptor.forClass(Object.class);
            org.mockito.ArgumentCaptor<Expiration> expiry =
                    org.mockito.ArgumentCaptor.forClass(Expiration.class);
            if (operation == CacheOperation.PUT) {
                verify(values).set(eq("cache:key"), stored.capture(), expiry.capture());
            } else {
                verify(values).setIfAbsent(eq("cache:key"), stored.capture(), expiry.capture());
            }
            assertThat(expiry.getValue().getTimeUnit()).isEqualTo(TimeUnit.SECONDS);
            assertThat(expiry.getValue().getExpirationTime()).isEqualTo(expected[i]);
            assertThat(expiry.getValue().getExpirationTimeInMilliseconds()).isEqualTo(expected[i] * 1000);
            assertThat(expiry.getValue().isPersistent()).isFalse();
            assertThat(expiry.getValue().isKeepTtl()).isFalse();
            assertThat(expiry.getValue().isUnixTimestamp()).isFalse();
            assertThat(stored.getValue()).isInstanceOf(CachedValue.class);
            assertThat(((CachedValue) stored.getValue()).getTtl()).isEqualTo(expected[i]);
        }
    }
}
