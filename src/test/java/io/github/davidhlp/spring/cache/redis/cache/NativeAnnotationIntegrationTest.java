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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CacheConfig;
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

    @Autowired @Qualifier("nativeLoadService") private NativeLoadService loadService;
    @Autowired private MethodNativeLoadService methodLoadService;
    @Autowired @Qualifier("nativeLoadState") private NativeLoadState loadState;
    @Autowired @Qualifier("methodLoadState") private NativeLoadState methodLoadState;
    @Autowired private BloomSupport bloomSupport;
    @Autowired private CacheOperationResolver operationResolver;
    @Autowired private SyncLockTimeout syncLockTimeout;
    @Autowired private MethodMetadataResolver metadataResolver;
    @Autowired private RedisCacheRegister register;
    @Autowired private RedisConnectionFactory connectionFactory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeClassSyncCoalescesConcurrentMissesUnderDistributedLock(boolean methodLevel) throws Exception {
        NativeLoadService loadService = methodLevel ? methodLoadService : this.loadService;
        NativeLoadState loadState = methodLevel ? this.methodLoadState : this.loadState;
        String key = "concurrent";
        cacheManager.getCache("users").evict(key);
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
            assertThat(cacheManager.getCache("users").get(key, String.class))
                    .isEqualTo("loaded-concurrent");
        } finally {
            loadState.release.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeClassSyncWithoutBackendFailsBeforeLoader(boolean methodLevel) {
        var properties = new RedisProCacheProperties();
        var noBackend = ResiCacheFeatures.builder()
                .bloomSupport(bloomSupport)
                .operationResolver(operationResolver)
                .syncSupport(new SyncSupport(new ArrayList<>(), properties))
                .syncLockTimeout(syncLockTimeout).build();
        var cache = new RedisProCache("users",
                RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory),
                RedisCacheConfiguration.defaultCacheConfig(), noBackend);
        var manager = new SimpleCacheManager();
        manager.setCaches(List.of(cache));
        manager.afterPropertiesSet();
        var source = new RedisCacheOperationSource(
                RedisProCacheProperties.NativeAnnotationMode.FULL, register);
        var advice = new RedisCacheInterceptor(source, manager,
                new SimpleKeyGenerator(), metadataResolver);
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("cacheManager", manager);
        beans.registerSingleton("nativeKeys", (org.springframework.cache.interceptor.KeyGenerator)
                (target, method, args) -> args[0]);
        beans.registerSingleton("nativeResolver", (org.springframework.cache.interceptor.CacheResolver)
                context -> List.of(cache));
        advice.setBeanFactory(beans);
        advice.afterSingletonsInstantiated();
        var state = new NativeLoadState();
        var factory = new ProxyFactory(methodLevel ? new MethodNativeLoadService(state, null, properties)
                : new NativeLoadService(state, null, properties));
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

    @CacheConfig(cacheNames = "users", keyGenerator = "nativeKeys", cacheManager = "cacheManager")
    @Cacheable(sync = true)
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
                    .buildLockKey("users::" + key);
            state.lockHeld = redisson.getLock(lockKey).isHeldByCurrentThread();
            state.entered.countDown();
            if (!state.release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Loader release timed out");
            }
            return "loaded-" + key;
        }
    }

    @CacheConfig(cacheNames = "users", keyGenerator = "nativeKeys", cacheResolver = "nativeResolver")
    static class MethodNativeLoadService extends NativeLoadService {
        MethodNativeLoadService(NativeLoadState state, RedissonClient redisson,
                RedisProCacheProperties properties) {
            super(state, redisson, properties);
        }

        @Override
        @Cacheable(sync = true)
        public String load(String key) throws InterruptedException {
            return super.load(key);
        }
    }

    @TestConfiguration
    static class NativeWriteConfig {
        @Bean org.springframework.cache.interceptor.KeyGenerator nativeKeys() {
            return (target, method, args) -> args[0];
        }
        @Bean org.springframework.cache.interceptor.CacheResolver nativeResolver(CacheManager manager) {
            return context -> List.of(manager.getCache("users"));
        }
        @Bean NativeLoadState nativeLoadState() { return new NativeLoadState(); }
        @Bean NativeLoadService nativeLoadService(@Qualifier("nativeLoadState") NativeLoadState state,
                RedissonClient redisson,
                RedisProCacheProperties properties) {
            return new NativeLoadService(state, redisson, properties);
        }
        @Bean NativeLoadState methodLoadState() { return new NativeLoadState(); }
        @Bean MethodNativeLoadService methodLoadService(@Qualifier("methodLoadState") NativeLoadState state,
                RedissonClient redisson, RedisProCacheProperties properties) {
            return new MethodNativeLoadService(state, redisson, properties);
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
