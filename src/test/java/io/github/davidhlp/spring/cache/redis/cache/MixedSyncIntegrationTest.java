package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.protection.breakdown.LockManager;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real AOP, public loader API and async writer must keep their result envelopes separate. */
@Import(MixedSyncIntegrationTest.Config.class)
class MixedSyncIntegrationTest extends AbstractRedisIntegrationTest {
    @Autowired private Service service;
    @Autowired private Gate gate;
    @Autowired private CacheManager cacheManager;
    @Autowired private RedisProCacheWriter writer;
    @Autowired private CacheOperationResolver resolver;

    @ParameterizedTest
    @CsvSource({"true,true", "false,true", "true,false", "false,false"})
    void mixedProtocolsKeepOwnResultAndPhysicalLock(boolean loaderLeader, boolean publicLoader) throws Exception {
        String key = "mixed-" + loaderLeader + "-" + publicLoader;
        MethodSnapshot snapshot = MethodSnapshot.of(Service.class.getMethod("load", String.class), Service.class);
        assertThat(service.load("register")).isEqualTo("value-register");
        gate.arm();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> loaderLeader ? load(snapshot, key, publicLoader) : retrieve(snapshot, key).join());
            assertThat(gate.entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> loaderLeader ? retrieve(snapshot, key).join()
                    : load(snapshot, key, publicLoader));
            try {
                await().during(java.time.Duration.ofMillis(200)).atMost(java.time.Duration.ofSeconds(2))
                        .until(() -> !second.isDone());
            } finally {
                gate.release.countDown();
            }
            Object firstValue = first.get(10, TimeUnit.SECONDS);
            Object secondValue = second.get(10, TimeUnit.SECONDS);
            if (loaderLeader) {
                assertThat(firstValue).isEqualTo(publicLoader ? "public-value" : "value-" + key);
                assertThat(secondValue).isInstanceOf(byte[].class);
                assertThat(service.load(key)).isEqualTo(firstValue);
            } else {
                assertThat(firstValue).isNull();
                assertThat(secondValue).isEqualTo(publicLoader ? "public-value" : "value-" + key);
                assertThat(service.load(key)).isEqualTo(secondValue);
            }
            assertThat(gate.lastKey).isEqualTo("mixed::" + key);
        } finally {
            gate.release.countDown();
        }
    }

    private String load(MethodSnapshot snapshot, String key, boolean publicLoader) {
        return publicLoader ? resolver.runWithSnapshot(snapshot, null,
                () -> cacheManager.getCache("mixed").get(key, () -> "public-value")) : service.load(key);
    }

    private CompletableFuture<byte[]> retrieve(MethodSnapshot snapshot, String key) {
        return resolver.runWithSnapshot(snapshot, null,
                () -> writer.retrieve("mixed", ("mixed::" + key).getBytes(StandardCharsets.UTF_8)));
    }

    @TestConfiguration
    static class Config {
        @Bean Service mixedService() { return new Service(); }
        @Bean Gate mixedGate(org.redisson.api.RedissonClient client,
                             io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties properties) {
            return new Gate(new DistributedLockManager(client, properties));
        }
    }

    static class Service {
        @RedisCacheable(value = "mixed", sync = true, syncTimeout = 5, ttl = 60)
        public String load(String key) { return "value-" + key; }
    }

    static class Gate implements LockManager {
        private final DistributedLockManager delegate;
        Gate(DistributedLockManager delegate) { this.delegate = delegate; }
        private final AtomicBoolean armed = new AtomicBoolean();
        private CountDownLatch entered;
        private CountDownLatch release;
        private volatile String lastKey;
        void arm() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            armed.set(true);
        }
        @Override public Optional<LockHandle> tryAcquire(String key, long seconds) throws InterruptedException {
            Optional<LockHandle> handle = delegate.tryAcquire(key, seconds);
            lastKey = key;
            if (armed.compareAndSet(true, false)) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test gate timed out");
                }
            }
            return handle;
        }
        @Override public int getOrder() { return Integer.MAX_VALUE; }
    }
}
