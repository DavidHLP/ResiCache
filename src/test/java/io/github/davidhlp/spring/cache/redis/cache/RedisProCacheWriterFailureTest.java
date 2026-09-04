package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.CacheHandlerChain;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import io.github.davidhlp.spring.cache.redis.chain.CacheHandlerChainFactory;
import io.github.davidhlp.spring.cache.redis.serialization.TypeSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisProCacheWriterFailureTest {

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

    private RedisProCacheWriter writer;

    @BeforeEach
    void setUp() {
        when(chainFactory.createChain()).thenReturn(chain);
        when(typeSupport.bytesToString(any())).thenReturn("cache::key");
        writer = new RedisProCacheWriter(
                redisTemplate, valueOperations, statistics, typeSupport, chainFactory, null);
    }

    @Test
    void putFailure_throwsTypedExceptionWithOriginalCause() {
        IllegalStateException cause = new IllegalStateException("redis down");
        when(chain.execute(any())).thenReturn(CacheResult.failure("PUT", "REDIS", cause));

        assertThatThrownBy(() -> writer.put("cache", "key".getBytes(), "value".getBytes(), Duration.ofSeconds(1)))
                .isInstanceOf(CacheOperationException.class)
                .hasCauseReference(cause)
                .satisfies(error -> {
                    CacheOperationException exception = (CacheOperationException) error;
                    assertThat(exception.getOperation()).isEqualTo("PUT");
                    assertThat(exception.getFailureKind()).isEqualTo("REDIS");
                });
    }

    @Test
    void serializationFailure_remainsDistinctFromRedisFailure() {
        io.github.davidhlp.spring.cache.redis.serialization.SerializationException failure =
                new io.github.davidhlp.spring.cache.redis.serialization.SerializationException(
                        "cannot deserialize");
        when(typeSupport.deserializeFromBytes(any())).thenThrow(failure);

        assertThatThrownBy(() -> writer.put(
                "cache", "key".getBytes(), "bad".getBytes(), Duration.ofSeconds(1)))
                .isSameAs(failure);
    }

    @Test
    void putIfAbsentFailure_isNotReportedAsExistingValue() {
        IllegalStateException cause = new IllegalStateException("redis down");
        when(chain.execute(any())).thenReturn(CacheResult.failure("PUT_IF_ABSENT", "REDIS", cause));

        assertThatThrownBy(() -> writer.putIfAbsent("cache", "key".getBytes(), "value".getBytes(), null))
                .isInstanceOf(CacheOperationException.class)
                .hasCauseReference(cause);
    }

    @Test
    void cleanFailure_throwsTypedException() {
        IllegalStateException cause = new IllegalStateException("redis down");
        when(chain.execute(any())).thenReturn(CacheResult.failure("CLEAN", "REDIS", cause));

        assertThatThrownBy(() -> writer.clean("cache", "cache::*".getBytes()))
                .isInstanceOf(CacheOperationException.class)
                .hasCauseReference(cause);
    }

    @Test
    void removeFailure_isObservableButBestEffort() {
        IllegalStateException cause = new IllegalStateException("redis down");
        when(chain.execute(any())).thenReturn(CacheResult.failure("REMOVE", "REDIS", cause));

        assertThatCode(() -> writer.remove("cache", "key".getBytes())).doesNotThrowAnyException();
    }
}
