package io.github.davidhlp.spring.cache.redis.annotation;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.CacheOperation;

import java.lang.reflect.Method;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@code nativeAnnotationMode=SELECTIVE} 消解双 Advisor 风险.
 *
 * <p>核心断言:在 SELECTIVE 模式下,纯 {@code @Cacheable} 方法不被 ResiCache
 * 的 {@link RedisCacheOperationSource} 接管(返回 {@code null}),因此 ResiCache
 * 的 {@code redisCacheAdvisor}(order=50)不会匹配该方法——只有 Spring 原生
 * {@code cacheAdvisor} 匹配,从 OperationSource 层消解双拦截。带
 * {@code @RedisCacheable} 的方法仍正常解析。
 */
class RedisCacheOperationSourceSelectiveTest {

    /** 仅带 Spring 原生 @Cacheable(无 ResiCache 注解)。 */
    static class NativeOnlyService {
        @Cacheable(value = "users", key = "#id")
        public String findById(Long id) {
            return "native";
        }
    }

    /** 带 ResiCache @RedisCacheable(防护入口)。 */
    static class ResiCacheService {
        @RedisCacheable(value = "users", key = "#id")
        public String findById(Long id) {
            return "resicache";
        }
    }

    private RedisCacheOperationSource selectiveSource() {
        return new RedisCacheOperationSource(RedisProCacheProperties.NativeAnnotationMode.SELECTIVE);
    }

    @Test
    void plainCacheableIsNotInterceptedUnderSelectiveMode() throws Exception {
        RedisCacheOperationSource source = selectiveSource();
        Method method = NativeOnlyService.class.getMethod("findById", Long.class);

        Collection<CacheOperation> ops = source.getCacheOperations(method, NativeOnlyService.class);

        // SELECTIVE: 纯 @Cacheable 不被接管 → 返回 null → redisCacheAdvisor 不匹配 → 无双 Advisor
        assertThat(ops).isNull();
    }

    @Test
    void resiCacheAnnotationIsResolvedUnderSelectiveMode() throws Exception {
        RedisCacheOperationSource source = selectiveSource();
        Method method = ResiCacheService.class.getMethod("findById", Long.class);

        Collection<CacheOperation> ops = source.getCacheOperations(method, ResiCacheService.class);

        // @RedisCacheable 仍正常解析为防护操作
        assertThat(ops).isNotNull().isNotEmpty();
    }
}
