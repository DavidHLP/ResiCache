package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties.NativeAnnotationMode;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.cache.interceptor.CacheOperationSource;

class NativeAnnotationBehaviorTest {
    public interface Operations {
        String put(String key);
        void evict(String key);
        String mixedPut(String key);
        void mixedEvict(String key);
    }

    public static class Service implements Operations {
        @CachePut(cacheNames = "native", key = "#p0")
        public String put(String key) { return "new"; }

        @CacheEvict(cacheNames = "native", key = "#p0")
        public void evict(String key) { }

        @RedisCacheable(cacheNames = "meta", condition = "false")
        @CachePut(cacheNames = "native", key = "#p0")
        public String mixedPut(String key) { return "new"; }

        @RedisCacheable(cacheNames = "meta", condition = "false")
        @CacheEvict(cacheNames = "native", key = "#p0")
        public void mixedEvict(String key) { }
    }

    @org.junit.jupiter.api.Test
    void fullModeUpdatesAndEvicts() {
        probe(new RedisCacheOperationSource(NativeAnnotationMode.FULL), false);
    }

    @org.junit.jupiter.api.Test
    void selectiveMixedModeUpdatesAndEvicts() {
        probe(new RedisCacheOperationSource(NativeAnnotationMode.SELECTIVE), true);
    }

    private static void probe(CacheOperationSource source, boolean mixed) {
        ConcurrentMapCacheManager manager = new ConcurrentMapCacheManager("native", "meta");
        CacheInterceptor interceptor = new CacheInterceptor();
        interceptor.setCacheOperationSource(source);
        interceptor.setCacheManager(manager);
        interceptor.afterPropertiesSet();
        interceptor.afterSingletonsInstantiated();
        ProxyFactory factory = new ProxyFactory(new Service());
        factory.addAdvice(interceptor);
        Operations service = (Operations) factory.getProxy();
        manager.getCache("native").put("k", "old");
        if (mixed) service.mixedPut("k"); else service.put("k");
        org.assertj.core.api.Assertions.assertThat(manager.getCache("native").get("k", String.class)).isEqualTo("new");
        if (mixed) service.mixedEvict("k"); else service.evict("k");
        org.assertj.core.api.Assertions.assertThat(manager.getCache("native").get("k")).isNull();
    }
}
