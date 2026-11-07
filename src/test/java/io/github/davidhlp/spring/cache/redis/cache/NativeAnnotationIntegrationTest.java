package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.SimpleKeyGenerator;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import static org.assertj.core.api.Assertions.assertThat;

/** Proves native annotation adaptation with the real cache manager, writer and Redis. */
@SpringBootTest(classes = TestApplication.class,
        properties = "resi-cache.native-annotation-mode=FULL")
@Import(NativeAnnotationIntegrationTest.NativeWriteConfig.class)
class NativeAnnotationIntegrationTest extends AbstractRedisIntegrationTest {
    @Autowired private CacheManager cacheManager;
    @Autowired private NativeWriteService service;

    @Test
    void nativePutReplacesOldValueAndEvictRemovesIt() {
        var cache = cacheManager.getCache("native-write");
        cache.put("key", "old");
        assertThat(service.put("key")).isEqualTo("new");
        assertThat(cache.get("key", String.class)).isEqualTo("new");
        service.evict("key");
        assertThat(cache.get("key")).isNull();
    }

    @Autowired private NativeLoadService loadService;
    @Autowired private NativeLoadState loadState;
    @Autowired private BloomSupport bloomSupport;
    @Autowired private CacheOperationResolver operationResolver;
    @Autowired private SyncLockTimeout syncLockTimeout;
    @Autowired private MethodMetadataResolver metadataResolver;
    @Autowired private RedisCacheRegister register;
    @Autowired private RedisConnectionFactory connectionFactory;

    @Test
    void nativeClassSyncCoalescesConcurrentMissesUnderDistributedLock() throws Exception {
        String key = "concurrent";
        cacheManager.getCache("native-class-sync").evict(key);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var ready = new CountDownLatch(8);
            var results = new ArrayList<Future<String>>();
            for (int i = 0; i < 8; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return loadService.load(key);
                }));
            }
            boolean allReady = ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            try {
                assertThat(allReady).isTrue();
                assertThat(loadState.entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(loadState.lockHeld).isTrue();
                assertThat(loadState.calls.get()).isEqualTo(1);
            } finally {
                loadState.release.countDown();
            }
            for (var result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo("loaded-concurrent");
            }
            assertThat(loadState.calls.get()).isEqualTo(1);
            assertThat(cacheManager.getCache("native-class-sync").get(key, String.class))
                    .isEqualTo("loaded-concurrent");
        } finally {
            loadState.release.countDown();
        }
    }

    @Test
    void nativeClassSyncWithoutBackendFailsBeforeLoader() {
        var properties = new RedisProCacheProperties();
        var noBackend = ResiCacheFeatures.builder()
                .bloomSupport(bloomSupport)
                .operationResolver(operationResolver)
                .syncSupport(new SyncSupport(new ArrayList<>(), properties))
                .syncLockTimeout(syncLockTimeout).build();
        var cache = new RedisProCache("native-class-sync",
                RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory),
                RedisCacheConfiguration.defaultCacheConfig(), noBackend);
        var manager = new SimpleCacheManager();
        manager.setCaches(List.of(cache));
        manager.afterPropertiesSet();
        var source = new RedisCacheOperationSource(
                RedisProCacheProperties.NativeAnnotationMode.FULL, register);
        var advice = new RedisCacheInterceptor(source, manager,
                new SimpleKeyGenerator(), metadataResolver);
        advice.afterSingletonsInstantiated();
        var state = new NativeLoadState();
        var factory = new ProxyFactory(new NativeLoadService(state, null, properties));
        factory.setProxyTargetClass(true);
        factory.addAdvice(advice);
        var proxy = (NativeLoadService) factory.getProxy();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> proxy.load("missing-backend"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("local-only=true");
        assertThat(state.calls.get()).isZero();
    }

    static class NativeLoadState {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean lockHeld;
    }

    @Cacheable(value = "native-class-sync", key = "#p0", sync = true)
    static class NativeLoadService {
        private final NativeLoadState state;
        private final RedissonClient redisson;
        private final RedisProCacheProperties properties;

        NativeLoadService(NativeLoadState state, RedissonClient redisson,
                RedisProCacheProperties properties) {
            this.state = state;
            this.redisson = redisson;
            this.properties = properties;
        }

        public String load(String key) throws InterruptedException {
            state.calls.incrementAndGet();
            if (redisson == null) {
                return "loaded-" + key;
            }
            String lockKey = new DistributedLockManager(redisson, properties)
                    .buildLockKey("native-class-sync::" + key);
            state.lockHeld = redisson.getLock(lockKey).isHeldByCurrentThread();
            state.entered.countDown();
            if (!state.release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Loader release timed out");
            }
            return "loaded-" + key;
        }
    }

    @TestConfiguration
    static class NativeWriteConfig {
        @Bean NativeLoadState nativeLoadState() { return new NativeLoadState(); }
        @Bean NativeLoadService nativeLoadService(NativeLoadState state,
                RedissonClient redisson,
                RedisProCacheProperties properties) {
            return new NativeLoadService(state, redisson, properties);
        }
        @Bean NativeWriteService nativeWriteService() { return new NativeWriteService(); }
    }

    static class NativeWriteService {
        @CachePut(cacheNames = "native-write", key = "#p0")
        public String put(String key) { return "new"; }

        @CacheEvict(cacheNames = "native-write", key = "#p0")
        public void evict(String key) { }
    }
}
