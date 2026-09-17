package io.github.davidhlp.spring.cache.redis.config;





import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
        @DisplayName("默认核心线程池大小为 2")
        void earlyExpiration_defaultPoolSize() {
            RedisProCacheProperties properties = new RedisProCacheProperties();
            assertThat(properties.getEarlyExpiration().getPoolSize()).isEqualTo(2);
        }

        @Test
        @DisplayName("默认最大线程池大小为 10")
        void earlyExpiration_defaultMaxPoolSize() {
            RedisProCacheProperties properties = new RedisProCacheProperties();
            assertThat(properties.getEarlyExpiration().getMaxPoolSize()).isEqualTo(10);
        }

        @Test
        @DisplayName("默认队列容量为 100")
        void earlyExpiration_defaultQueueCapacity() {
            RedisProCacheProperties properties = new RedisProCacheProperties();
            assertThat(properties.getEarlyExpiration().getQueueCapacity()).isEqualTo(100);
        }
    }


    @Nested
    @DisplayName("提前过期配置")
    class EarlyExpirationPropertiesTests {

        @Test
        @DisplayName("设置核心线程池大小")
        void setPoolSize_changesValue() {
            RedisProCacheProperties.EarlyExpirationProperties earlyExpiration = new RedisProCacheProperties.EarlyExpirationProperties();
            earlyExpiration.setPoolSize(4);
            assertThat(earlyExpiration.getPoolSize()).isEqualTo(4);
        }

        @Test
        @DisplayName("设置最大线程池大小")
        void setMaxPoolSize_changesValue() {
            RedisProCacheProperties.EarlyExpirationProperties earlyExpiration = new RedisProCacheProperties.EarlyExpirationProperties();
            earlyExpiration.setMaxPoolSize(20);
            assertThat(earlyExpiration.getMaxPoolSize()).isEqualTo(20);
        }

        @Test
        @DisplayName("设置队列容量")
        void setQueueCapacity_changesValue() {
            RedisProCacheProperties.EarlyExpirationProperties earlyExpiration = new RedisProCacheProperties.EarlyExpirationProperties();
            earlyExpiration.setQueueCapacity(200);
            assertThat(earlyExpiration.getQueueCapacity()).isEqualTo(200);
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
            properties.setDisabledHandlers(java.util.List.of("early-expiration"));
            assertThat(properties.getDisabledHandlers()).containsExactly("early-expiration");
        }


        @Test
        @DisplayName("设置提前过期配置")
        void setEarlyExpiration_changesConfig() {
            RedisProCacheProperties properties = new RedisProCacheProperties();
            RedisProCacheProperties.EarlyExpirationProperties earlyExpiration = new RedisProCacheProperties.EarlyExpirationProperties();
            earlyExpiration.setPoolSize(8);
            properties.setEarlyExpiration(earlyExpiration);

            assertThat(properties.getEarlyExpiration().getPoolSize()).isEqualTo(8);
        }
    }
}
