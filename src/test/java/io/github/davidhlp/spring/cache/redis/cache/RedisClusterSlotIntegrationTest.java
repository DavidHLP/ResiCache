package io.github.davidhlp.spring.cache.redis.cache;






import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Redis Cluster proof for cache-key/distributed-lock slot co-location.
 */
@SpringBootTest(classes = TestApplication.class)
@Import(RedisClusterSlotIntegrationTest.ClusterLoadService.class)
@DisplayName("Redis Cluster Slot Integration Tests")
class RedisClusterSlotIntegrationTest extends AbstractRedisClusterIntegrationTest {

    private static final String CACHE_NAME = "clusterSlot";
    private static final String ACTUAL_KEY = "customer-42";
    private static final String CACHE_KEY = CACHE_NAME + "::" + ACTUAL_KEY;

    @Autowired
    private ClusterLoadService loadService;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private RedisProCacheProperties properties;

    @Test
    @DisplayName("sync cache PUT pins the live Redisson lock key to the cache key slot")
    void syncPut_lockAndCacheKeyShareRealClusterSlot() throws Exception {
        assertThat(properties.getRedis().getMode()).isEqualTo("cluster");
        assertThat(redissonClient.getConfig().isClusterConfig()).isTrue();

        CompletableFuture<String> load = CompletableFuture.supplyAsync(
                () -> loadService.load(ACTUAL_KEY));
        assertThat(loadService.awaitEntered(10, TimeUnit.SECONDS)).isTrue();

        try {
            String lockKey = awaitLiveLockKey();
            int cacheSlot = Integer.parseInt(redisCli("cluster", "keyslot", CACHE_KEY));
            int lockSlot = Integer.parseInt(redisCli("cluster", "keyslot", lockKey));

            assertThat(lockSlot).isEqualTo(cacheSlot);
            assertThat(redisCli("-c", "exists", CACHE_KEY, lockKey))
                    .as("two-key command must execute on the Cluster without CROSSSLOT")
                    .isEqualTo("1");
        } finally {
            loadService.release();
        }
        assertThat(load.get(10, TimeUnit.SECONDS)).isEqualTo("value-customer-42");
        assertThat(redisCli("-c", "exists", CACHE_KEY)).isEqualTo("1");
        assertThat(redisCli("-c", "get", CACHE_KEY)).contains("value-customer-42");
    }

    private String awaitLiveLockKey() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            for (int port = 7000; port < 7003; port++) {
                String keys = redisCliAt(port, "--scan", "--pattern",
                        properties.getSyncLock().getPrefix() + "*");
                List<String> lockKeys = Arrays.stream(keys.split("\\R"))
                        .filter(key -> !key.isBlank())
                        .toList();
                if (lockKeys.size() == 1) {
                    return lockKeys.getFirst();
                }
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new IllegalStateException("No live Redisson lock key observed in Redis Cluster");
    }

    @Service
    static class ClusterLoadService {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @RedisCacheable(cacheNames = CACHE_NAME, key = "#key", sync = true, ttl = 60)
        public String load(String key) {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release Cluster loader");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Cluster loader interrupted", ex);
            }
            return "value-" + key;
        }

        boolean awaitEntered(long timeout, TimeUnit unit) throws InterruptedException {
            return entered.await(timeout, unit);
        }

        void release() {
            release.countDown();
        }
    }
}
