package io.github.davidhlp.spring.cache.redis;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKeyGenerator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sync Single-Flight Integration Test.
 *
 * <p>Proves that {@code sync = true} on {@code @RedisCacheable} prevents concurrent
 * method invocations under cache miss, guaranteeing single-flight loading.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Import({TestRedisConfiguration.class, SyncSingleFlightIntegrationTest.TestConfig.class})
@DisplayName("Sync Single-Flight Integration Tests")
class SyncSingleFlightIntegrationTest extends AbstractRedisIntegrationTest {

    @Autowired
    private LoadService loadService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RedisTemplate<String, Object> redisCacheTemplate;

    @Autowired
    private io.github.davidhlp.spring.cache.redis.register.RedisCacheRegister redisCacheRegister;

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @BeforeEach
    void setUp() {
        redisCacheTemplate.getConnectionFactory().getConnection().flushDb();
        loadService.resetCounter();
        cacheManager.getCache("test").clear();
    }

    @Test
    @DisplayName("operation metadata is registered for sync method")
    void operationMetadata_registered() throws NoSuchMethodException {
        String arg = "diag-key";
        loadService.loadExpensiveData(arg);

        Method method = LoadService.class.getMethod("loadExpensiveData", String.class);
        org.springframework.context.expression.AnnotatedElementKey elementKey =
                new org.springframework.context.expression.AnnotatedElementKey(method, LoadService.class);
        var operation = redisCacheRegister.getCacheableOperation("test", elementKey);

        assertThat(operation).isNotNull();
        assertThat(operation.isSync()).isTrue();
    }

    @Test
    @DisplayName("proxy method has annotation via AnnotatedElementUtils")
    void proxyMethod_hasAnnotation() throws NoSuchMethodException {
        Class<?> proxyClass = loadService.getClass();
        Method proxyMethod = proxyClass.getMethod("loadExpensiveData", String.class);
        var ann = org.springframework.core.annotation.AnnotatedElementUtils
                .findMergedAnnotation(proxyMethod, RedisCacheable.class);
        assertThat(ann).isNotNull();
    }

    @Test
    @DisplayName("cache manager returns RedisProCache")
    void cacheManager_returnsRedisProCache() {
        var cache = cacheManager.getCache("test");
        assertThat(cache).isInstanceOf(io.github.davidhlp.spring.cache.redis.core.RedisProCache.class);
    }

    @Test
    @DisplayName("sync=true works in single-threaded scenario")
    void syncTrue_singleThread_methodInvokedOnce() {
        String arg = "single-key";
        String result1 = loadService.loadExpensiveData(arg);

        // 诊断：检查 Redis 中是否有缓存值
        String redisKey = "test::" + arg;
        Boolean hasKey = redisCacheTemplate.hasKey(redisKey);
        Object rawValue = redisCacheTemplate.opsForValue().get(redisKey);

        String result2 = loadService.loadExpensiveData(arg);
        assertThat(loadService.getInvocationCount()).isEqualTo(1);
        assertThat(result1).isEqualTo(result2);
    }

    @Test
    @DisplayName("sync=true prevents concurrent method invocations on cache miss")
    void syncTrue_singleFlight_methodInvokedExactlyOnce() throws InterruptedException {
        int threadCount = 10;
        String arg = "shared-key";
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);
        String[] results = new String[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    results[index] = loadService.loadExpensiveData(arg);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completeLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = completeLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed)
                .as("All threads should complete within timeout")
                .isTrue();
        assertThat(loadService.getInvocationCount())
                .as("Method should be invoked exactly once despite 10 concurrent threads")
                .isEqualTo(1);

        String firstResult = results[0];
        for (int i = 1; i < threadCount; i++) {
            assertThat(results[i])
                    .as("All threads should receive the same cached result")
                    .isEqualTo(firstResult);
        }
    }

    @Test
    @DisplayName("sync=true allows re-invocation after cache eviction")
    void syncTrue_afterEviction_allowsReinvocation() throws InterruptedException {
        String arg = "evict-key";

        String result1 = loadService.loadExpensiveData(arg);
        assertThat(loadService.getInvocationCount()).isEqualTo(1);

        cacheManager.getCache("test").evict(arg);

        String result2 = loadService.loadExpensiveData(arg);
        assertThat(loadService.getInvocationCount()).isEqualTo(2);
        assertThat(result2).isEqualTo("expensive-" + arg + "-2");
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        public LoadService loadService() {
            return new LoadService();
        }

        /**
         * 提供明确的 @Primary KeyGenerator，防止其他测试类泄漏的 customKeyGenerator
         * 影响本测试的缓存 key 生成（key 不一致会导致 evict 无法命中实际写入的 key）。
         */
        @Bean
        @Primary
        public KeyGenerator keyGenerator() {
            return new SimpleKeyGenerator();
        }
    }

    static class LoadService {

        private final AtomicInteger counter = new AtomicInteger(0);

        @RedisCacheable(value = "test", sync = true, ttl = 60)
        public String loadExpensiveData(String key) {
            int count = counter.incrementAndGet();
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "expensive-" + key + "-" + count;
        }

        public int getInvocationCount() {
            return counter.get();
        }

        public void resetCounter() {
            counter.set(0);
        }
    }
}
