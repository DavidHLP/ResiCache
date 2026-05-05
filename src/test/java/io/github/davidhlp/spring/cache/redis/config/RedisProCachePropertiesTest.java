package io.github.davidhlp.spring.cache.redis.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisProCacheProperties 单元测试
 */
@DisplayName("RedisProCacheProperties Tests")
class RedisProCachePropertiesTest {

    @Nested
    @DisplayName("默认配置")
    class DefaultValuesTests {

        @Test
        @DisplayName("默认 TTL 为 30 分钟")
        void defaultTtl_is30Minutes() {
            RedisProCacheProperties properties = new RedisProCacheProperties();
            assertThat(properties.getDefaultTtl()).isEqualTo(Duration.ofMinutes(30));
        }

        @Test
        @DisplayName("默认启用布隆过滤器")
        void bloomFilter_enabledByDefault() {
            RedisProCacheProperties properties = new RedisProCacheProperties();
            assertThat(properties.getBloomFilter().isEnabled()).isTrue();
        }

        @Test
        @DisplayName("默认启用预刷新")
        void preRefresh_enabledByDefault() {
            RedisProCacheProperties properties = new RedisProCacheProperties();
            assertThat(properties.getPreRefresh().isEnabled()).isTrue();
        }

        @Test
        @DisplayName("默认核心线程池大小为 2")
        void preRefresh_defaultPoolSize() {
            RedisProCacheProperties properties = new RedisProCacheProperties();
            assertThat(properties.getPreRefresh().getPoolSize()).isEqualTo(2);
        }

        @Test
        @DisplayName("默认最大线程池大小为 10")
        void preRefresh_defaultMaxPoolSize() {
            RedisProCacheProperties properties = new RedisProCacheProperties();
            assertThat(properties.getPreRefresh().getMaxPoolSize()).isEqualTo(10);
        }

        @Test
        @DisplayName("默认队列容量为 100")
        void preRefresh_defaultQueueCapacity() {
            RedisProCacheProperties properties = new RedisProCacheProperties();
            assertThat(properties.getPreRefresh().getQueueCapacity()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("布隆过滤器配置")
    class BloomFilterPropertiesTests {

        @Test
        @DisplayName("设置布隆过滤器启用状态")
        void setEnabled_changesValue() {
            RedisProCacheProperties.BloomFilterProperties bloomFilter = new RedisProCacheProperties.BloomFilterProperties();
            bloomFilter.setEnabled(false);
            assertThat(bloomFilter.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("默认预期插入数量为 100000")
        void defaultExpectedInsertions() {
            RedisProCacheProperties.BloomFilterProperties bloomFilter = new RedisProCacheProperties.BloomFilterProperties();
            assertThat(bloomFilter.getExpectedInsertions()).isEqualTo(100000L);
        }

        @Test
        @DisplayName("默认期望误判率为 0.01")
        void defaultFalseProbability() {
            RedisProCacheProperties.BloomFilterProperties bloomFilter = new RedisProCacheProperties.BloomFilterProperties();
            assertThat(bloomFilter.getFalseProbability()).isEqualTo(0.01);
        }

        @Test
        @DisplayName("默认哈希缓存大小为 10000")
        void defaultHashCacheSize() {
            RedisProCacheProperties.BloomFilterProperties bloomFilter = new RedisProCacheProperties.BloomFilterProperties();
            assertThat(bloomFilter.getHashCacheSize()).isEqualTo(10000);
        }

        @Test
        @DisplayName("设置预期插入数量")
        void setExpectedInsertions_changesValue() {
            RedisProCacheProperties.BloomFilterProperties bloomFilter = new RedisProCacheProperties.BloomFilterProperties();
            bloomFilter.setExpectedInsertions(500000L);
            assertThat(bloomFilter.getExpectedInsertions()).isEqualTo(500000L);
        }

        @Test
        @DisplayName("设置期望误判率")
        void setFalseProbability_changesValue() {
            RedisProCacheProperties.BloomFilterProperties bloomFilter = new RedisProCacheProperties.BloomFilterProperties();
            bloomFilter.setFalseProbability(0.001);
            assertThat(bloomFilter.getFalseProbability()).isEqualTo(0.001);
        }
    }

    @Nested
    @DisplayName("预刷新配置")
    class PreRefreshPropertiesTests {

        @Test
        @DisplayName("设置核心线程池大小")
        void setPoolSize_changesValue() {
            RedisProCacheProperties.PreRefreshProperties preRefresh = new RedisProCacheProperties.PreRefreshProperties();
            preRefresh.setPoolSize(4);
            assertThat(preRefresh.getPoolSize()).isEqualTo(4);
        }

        @Test
        @DisplayName("设置最大线程池大小")
        void setMaxPoolSize_changesValue() {
            RedisProCacheProperties.PreRefreshProperties preRefresh = new RedisProCacheProperties.PreRefreshProperties();
            preRefresh.setMaxPoolSize(20);
            assertThat(preRefresh.getMaxPoolSize()).isEqualTo(20);
        }

        @Test
        @DisplayName("设置队列容量")
        void setQueueCapacity_changesValue() {
            RedisProCacheProperties.PreRefreshProperties preRefresh = new RedisProCacheProperties.PreRefreshProperties();
            preRefresh.setQueueCapacity(200);
            assertThat(preRefresh.getQueueCapacity()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("同步锁配置")
    class SyncLockPropertiesTests {

        @Test
        @DisplayName("默认超时时间为 3000 毫秒")
        void defaultTimeout() {
            RedisProCacheProperties.SyncLockProperties syncLock = new RedisProCacheProperties.SyncLockProperties();
            assertThat(syncLock.getTimeout()).isEqualTo(3000L);
        }

        @Test
        @DisplayName("默认时间单位为毫秒")
        void defaultTimeUnit() {
            RedisProCacheProperties.SyncLockProperties syncLock = new RedisProCacheProperties.SyncLockProperties();
            assertThat(syncLock.getUnit()).isEqualTo(TimeUnit.MILLISECONDS);
        }

        @Test
        @DisplayName("设置超时时间")
        void setTimeout_changesValue() {
            RedisProCacheProperties.SyncLockProperties syncLock = new RedisProCacheProperties.SyncLockProperties();
            syncLock.setTimeout(5000L);
            assertThat(syncLock.getTimeout()).isEqualTo(5000L);
        }

        @Test
        @DisplayName("设置时间单位")
        void setUnit_changesValue() {
            RedisProCacheProperties.SyncLockProperties syncLock = new RedisProCacheProperties.SyncLockProperties();
            syncLock.setUnit(TimeUnit.SECONDS);
            assertThat(syncLock.getUnit()).isEqualTo(TimeUnit.SECONDS);
        }
    }

    @Nested
    @DisplayName("Redisson 配置")
    class RedissonPropertiesTests {

        @Test
        @DisplayName("默认连接池大小为 64")
        void defaultConnectionPoolSize() {
            RedisProCacheProperties.RedissonProperties redisson = new RedisProCacheProperties.RedissonProperties();
            assertThat(redisson.getConnectionPoolSize()).isEqualTo(64);
        }

        @Test
        @DisplayName("默认最小空闲连接数为 10")
        void defaultConnectionMinimumIdleSize() {
            RedisProCacheProperties.RedissonProperties redisson = new RedisProCacheProperties.RedissonProperties();
            assertThat(redisson.getConnectionMinimumIdleSize()).isEqualTo(10);
        }

        @Test
        @DisplayName("默认空闲连接超时为 10000 毫秒")
        void defaultIdleConnectionTimeout() {
            RedisProCacheProperties.RedissonProperties redisson = new RedisProCacheProperties.RedissonProperties();
            assertThat(redisson.getIdleConnectionTimeout()).isEqualTo(10000);
        }

        @Test
        @DisplayName("默认连接超时为 10000 毫秒")
        void defaultConnectTimeout() {
            RedisProCacheProperties.RedissonProperties redisson = new RedisProCacheProperties.RedissonProperties();
            assertThat(redisson.getConnectTimeout()).isEqualTo(10000);
        }

        @Test
        @DisplayName("默认命令超时为 3000 毫秒")
        void defaultTimeout() {
            RedisProCacheProperties.RedissonProperties redisson = new RedisProCacheProperties.RedissonProperties();
            assertThat(redisson.getTimeout()).isEqualTo(3000);
        }

        @Test
        @DisplayName("默认重试次数为 3")
        void defaultRetryAttempts() {
            RedisProCacheProperties.RedissonProperties redisson = new RedisProCacheProperties.RedissonProperties();
            assertThat(redisson.getRetryAttempts()).isEqualTo(3);
        }

        @Test
        @DisplayName("默认重试间隔为 1500 毫秒")
        void defaultRetryInterval() {
            RedisProCacheProperties.RedissonProperties redisson = new RedisProCacheProperties.RedissonProperties();
            assertThat(redisson.getRetryInterval()).isEqualTo(1500);
        }

        @Test
        @DisplayName("设置连接池大小")
        void setConnectionPoolSize_changesValue() {
            RedisProCacheProperties.RedissonProperties redisson = new RedisProCacheProperties.RedissonProperties();
            redisson.setConnectionPoolSize(128);
            assertThat(redisson.getConnectionPoolSize()).isEqualTo(128);
        }
    }

    @Nested
    @DisplayName("Handler 配置")
    class HandlerConfigTests {

        @Test
        @DisplayName("默认禁用处理器列表为空")
        void defaultDisabledHandlersList() {
            RedisProCacheProperties.HandlerConfig config = new RedisProCacheProperties.HandlerConfig();
            assertThat(config.getDisabledHandlers()).isEmpty();
        }

        @Test
        @DisplayName("设置禁用处理器列表")
        void setDisabledHandlers_changesList() {
            RedisProCacheProperties.HandlerConfig config = new RedisProCacheProperties.HandlerConfig();
            config.setDisabledHandlers(java.util.List.of("bloom-filter", "sync-lock"));
            assertThat(config.getDisabledHandlers()).containsExactly("bloom-filter", "sync-lock");
        }
    }

    @Nested
    @DisplayName("顶层属性配置")
    class TopLevelPropertiesTests {

        @Test
        @DisplayName("设置默认 TTL")
        void setDefaultTtl_changesValue() {
            RedisProCacheProperties properties = new RedisProCacheProperties();
            properties.setDefaultTtl(Duration.ofHours(1));
            assertThat(properties.getDefaultTtl()).isEqualTo(Duration.ofHours(1));
        }

        @Test
        @DisplayName("设置禁用的 Handler 列表")
        void setDisabledHandlers_changesList() {
            RedisProCacheProperties properties = new RedisProCacheProperties();
            properties.setDisabledHandlers(java.util.List.of("pre-refresh"));
            assertThat(properties.getDisabledHandlers()).containsExactly("pre-refresh");
        }

        @Test
        @DisplayName("设置 Handler 设置")
        void setHandlerSettings_changesMap() {
            RedisProCacheProperties properties = new RedisProCacheProperties();
            RedisProCacheProperties.HandlerConfig config = new RedisProCacheProperties.HandlerConfig();
            config.setDisabledHandlers(java.util.List.of("bloom-filter"));
            properties.setHandlerSettings(java.util.Map.of("user-cache", config));

            assertThat(properties.getHandlerSettings()).containsKey("user-cache");
            assertThat(properties.getHandlerSettings().get("user-cache").getDisabledHandlers())
                    .containsExactly("bloom-filter");
        }

        @Test
        @DisplayName("设置布隆过滤器配置")
        void setBloomFilter_changesConfig() {
            RedisProCacheProperties properties = new RedisProCacheProperties();
            RedisProCacheProperties.BloomFilterProperties bloomFilter = new RedisProCacheProperties.BloomFilterProperties();
            bloomFilter.setEnabled(false);
            properties.setBloomFilter(bloomFilter);

            assertThat(properties.getBloomFilter().isEnabled()).isFalse();
        }

        @Test
        @DisplayName("设置预刷新配置")
        void setPreRefresh_changesConfig() {
            RedisProCacheProperties properties = new RedisProCacheProperties();
            RedisProCacheProperties.PreRefreshProperties preRefresh = new RedisProCacheProperties.PreRefreshProperties();
            preRefresh.setPoolSize(8);
            properties.setPreRefresh(preRefresh);

            assertThat(properties.getPreRefresh().getPoolSize()).isEqualTo(8);
        }
    }
}
