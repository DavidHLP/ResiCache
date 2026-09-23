package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisProCacheWriterStatisticsTest {

    private static final String CACHE_NAME = "stats-cache";
    private static final byte[] KEY = "stats-cache::key".getBytes();
    private static final byte[] VALUE = "value".getBytes();

    /** 真实 no-metadata resolver(无激活上下文 → resolve 恒 null),取代旧的「传 null 关闭解析」。 */
    private static final CacheOperationResolver NO_METADATA_RESOLVER =
            new CacheOperationResolver(new DefaultMethodMetadataResolver(), new RedisCacheRegister());

    private CacheStatisticsCollector oldCollector;


    @Mock
    private CacheValueCodec valueCodec;

    @Mock
    private CacheHandlerChainFactory chainFactory;

    @Mock
    private CacheHandlerChain chain;

    private RedisProCacheWriter writer;

    @BeforeEach
    void setUp() {
        oldCollector = CacheStatisticsCollector.create();
        when(chainFactory.createChain()).thenReturn(chain);
        writer = new RedisProCacheWriter(
                oldCollector, valueCodec, chainFactory, NO_METADATA_RESOLVER);
    }

    @Test
    void writerOperations_recordSpringStatisticsAtOperationBoundary() {
        CacheStatisticsCollector collector = CacheStatisticsCollector.create();
        writer = new RedisProCacheWriter(
                collector, valueCodec, chainFactory, NO_METADATA_RESOLVER);

        when(chain.execute(any())).thenReturn(CacheResult.miss());
        assertThat(writer.get(CACHE_NAME, KEY)).isNull();

        when(chain.execute(any())).thenReturn(CacheResult.success(VALUE));
        assertThat(writer.get(CACHE_NAME, KEY)).isEqualTo(VALUE);

        when(chain.execute(any())).thenReturn(CacheResult.success());
        writer.put(CACHE_NAME, KEY, VALUE, Duration.ofSeconds(1));

        when(chain.execute(any())).thenReturn(CacheResult.inserted());
        assertThat(writer.putIfAbsent(CACHE_NAME, KEY, VALUE, null)).isNull();

        when(chain.execute(any())).thenReturn(CacheResult.success());
        writer.evict(CACHE_NAME, KEY);

        when(chain.execute(any())).thenReturn(CacheResult.successWithDeletedCount(2));
        writer.clear(CACHE_NAME, "stats-cache::*".getBytes());

        CacheStatistics statistics = collector.getCacheStatistics(CACHE_NAME);
        assertThat(statistics.getGets()).isEqualTo(2);
        assertThat(statistics.getHits()).isEqualTo(1);
        assertThat(statistics.getMisses()).isEqualTo(1);
        assertThat(statistics.getPuts()).isEqualTo(2);
        assertThat(statistics.getDeletes()).isEqualTo(3);
    }

    @Test
    void withStatisticsCollector_rebindsWriterWithoutOldCollectorGrowth() {
        CacheStatisticsCollector replacementCollector = CacheStatisticsCollector.create();
        RedisCacheWriter replacement = writer.withStatisticsCollector(replacementCollector);

        when(chain.execute(any())).thenReturn(CacheResult.miss());
        assertThat(replacement.get(CACHE_NAME, KEY)).isNull();

        CacheStatistics oldStatistics = oldCollector.getCacheStatistics(CACHE_NAME);
        CacheStatistics newStatistics = replacementCollector.getCacheStatistics(CACHE_NAME);
        assertThat(oldStatistics.getGets()).isZero();
        assertThat(oldStatistics.getMisses()).isZero();
        assertThat(newStatistics.getGets()).isEqualTo(1);
        assertThat(newStatistics.getMisses()).isEqualTo(1);
    }
}
