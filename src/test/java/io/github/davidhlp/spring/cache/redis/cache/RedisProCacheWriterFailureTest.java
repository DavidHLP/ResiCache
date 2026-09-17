package io.github.davidhlp.spring.cache.redis.cache;





import io.github.davidhlp.spring.cache.redis.chain.CacheOperation;
import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisProCacheWriterFailureTest {


    @Mock
    private CacheStatisticsCollector statistics;


    @Mock
    private CacheValueCodec valueCodec;

    @Mock
    private CacheHandlerChainFactory chainFactory;

    @Mock
    private CacheHandlerChain chain;

    private RedisProCacheWriter writer;

    @BeforeEach
    void setUp() {
        when(chainFactory.createChain()).thenReturn(chain);
        writer = new RedisProCacheWriter(
                statistics, valueCodec, chainFactory, null);
    }

    @Test
    void putFailure_throwsTypedExceptionWithOriginalCause() {
        IllegalStateException cause = new IllegalStateException("redis down");
        when(chain.execute(any())).thenReturn(CacheResult.failure(CacheOperation.PUT, CacheResult.FailureKind.REDIS, cause));

        assertThatThrownBy(() -> writer.put("cache", "key".getBytes(), "value".getBytes(), Duration.ofSeconds(1)))
                .isInstanceOf(CacheOperationException.class)
                .hasCauseReference(cause)
                .satisfies(error -> {
                    CacheOperationException exception = (CacheOperationException) error;
                    assertThat(exception.getOperation()).isEqualTo(CacheOperation.PUT);
                    assertThat(exception.getFailureKind()).isEqualTo(CacheResult.FailureKind.REDIS);
                });
    }

    @Test
    void serializationFailure_remainsDistinctFromRedisFailure() {
        io.github.davidhlp.spring.cache.redis.serialization.SerializationException failure =
                new io.github.davidhlp.spring.cache.redis.serialization.SerializationException(
                        "cannot deserialize");
        when(valueCodec.fromValueBytes(any())).thenThrow(failure);

        assertThatThrownBy(() -> writer.put(
                "cache", "key".getBytes(), "bad".getBytes(), Duration.ofSeconds(1)))
                .isSameAs(failure);
    }

    @Test
    void putIfAbsentFailure_isNotReportedAsExistingValue() {
        IllegalStateException cause = new IllegalStateException("redis down");
        when(chain.execute(any())).thenReturn(CacheResult.failure(CacheOperation.PUT_IF_ABSENT, CacheResult.FailureKind.REDIS, cause));

        assertThatThrownBy(() -> writer.putIfAbsent("cache", "key".getBytes(), "value".getBytes(), null))
                .isInstanceOf(CacheOperationException.class)
                .hasCauseReference(cause);
    }

    @Test
    void cleanFailure_throwsTypedException() {
        IllegalStateException cause = new IllegalStateException("redis down");
        when(chain.execute(any())).thenReturn(CacheResult.failure(CacheOperation.CLEAN, CacheResult.FailureKind.REDIS, cause));

        assertThatThrownBy(() -> writer.clear("cache", "cache::*".getBytes()))
                .isInstanceOf(CacheOperationException.class)
                .hasCauseReference(cause);
    }

    @Test
    void removeFailure_isObservableButBestEffort() {
        IllegalStateException cause = new IllegalStateException("redis down");
        when(chain.execute(any())).thenReturn(CacheResult.failure(CacheOperation.REMOVE, CacheResult.FailureKind.REDIS, cause));

        assertThatCode(() -> writer.evict("cache", "key".getBytes())).doesNotThrowAnyException();
    }

}
