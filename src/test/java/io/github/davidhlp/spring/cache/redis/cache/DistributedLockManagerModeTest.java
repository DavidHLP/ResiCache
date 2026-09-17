package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("DistributedLockManager deployment mode tests")
class DistributedLockManagerModeTest {

    @Test
    @DisplayName("trimmed cluster mode uses the cluster lock-key branch")
    void paddedClusterMode_buildLockKeyUsesClusterHashTag() {
        RedisProCacheProperties properties = new RedisProCacheProperties();
        properties.getRedis().setMode(" cluster ");
        DistributedLockManager lockManager =
                new DistributedLockManager(mock(RedissonClient.class), properties);

        assertThat(properties.getRedis().getMode()).isEqualTo("cluster");

        assertThat(lockManager.buildLockKey("users:123"))
                .isEqualTo("cache:lock:{users:123}");
    }

    @Test
    @DisplayName("padded single and sentinel modes normalize before plain lock-key handling")
    void paddedNonClusterModes_usePlainLockKeyAndNormalize() {
        for (String mode : new String[]{" single ", " sentinel "}) {
            RedisProCacheProperties properties = new RedisProCacheProperties();
            properties.getRedis().setMode(mode);
            DistributedLockManager lockManager =
                    new DistributedLockManager(mock(RedissonClient.class), properties);

            assertThat(properties.getRedis().getMode()).isEqualTo(mode.trim());
            assertThat(lockManager.buildLockKey("users:123"))
                    .isEqualTo("cache:lock:users:123");
        }
    }
}
