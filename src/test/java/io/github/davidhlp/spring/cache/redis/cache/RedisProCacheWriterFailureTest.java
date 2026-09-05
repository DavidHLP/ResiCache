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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
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
        when(typeSupport.deserializeFromBytes(any())).thenThrow(failure);

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

        assertThatThrownBy(() -> writer.clean("cache", "cache::*".getBytes()))
                .isInstanceOf(CacheOperationException.class)
                .hasCauseReference(cause);
    }

    @Test
    void removeFailure_isObservableButBestEffort() {
        IllegalStateException cause = new IllegalStateException("redis down");
        when(chain.execute(any())).thenReturn(CacheResult.failure(CacheOperation.REMOVE, CacheResult.FailureKind.REDIS, cause));

        assertThatCode(() -> writer.remove("cache", "key".getBytes())).doesNotThrowAnyException();
    }

    @Test
    void loaderPath_writeBackFailure_returnsLoadedBytesAvailabilityFirst() {
        // cache miss → loader 成功 → 写回(链 PUT)失败。ADR-02:必须返回 loader 值。
        when(chain.execute(any()))
                .thenReturn(CacheResult.miss())   // GET:miss → loader
                .thenReturn(CacheResult.failure(CacheOperation.PUT, CacheResult.FailureKind.REDIS, new IllegalStateException("redis down"))); // PUT:失败

        byte[] loaded = "loaded".getBytes();
        byte[] result = writer.get(
                "cache", "key".getBytes(), () -> loaded, Duration.ofSeconds(1), false);

        assertThat(result)
                .as("loader 值必须穿透写回失败返回(availability-first)")
                .isSameAs(loaded);
    }

    @Test
    void loaderPath_loaderFailure_propagates() {
        // cache miss → loader 抛异常 → 异常必须原样传播(不得吞/降级)
        when(chain.execute(any())).thenReturn(CacheResult.miss());

        IllegalStateException boom = new IllegalStateException("business loader failed");
        assertThatThrownBy(() -> writer.get(
                "cache", "key".getBytes(),
                () -> { throw boom; },
                Duration.ofSeconds(1), false))
                .isSameAs(boom);
    }

    @Test
    void loaderPath_cacheHit_returnsCachedBytesWithoutLoading() {
        // 缓存命中 → 直接返回缓存字节,loader 不被调用
        when(chain.execute(any())).thenReturn(CacheResult.success("cached".getBytes()));

        byte[] result = writer.get(
                "cache", "key".getBytes(),
                () -> { throw new AssertionError("loader must not run on cache hit"); },
                Duration.ofSeconds(1), false);

        assertThat(result).isEqualTo("cached".getBytes());
    }
}
