package io.github.davidhlp.spring.cache.redis.config;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheOperationSource;
import io.github.davidhlp.spring.cache.redis.cache.ResiCacheMethodInterceptor;
import io.github.davidhlp.spring.cache.redis.handler.CachePutAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.CacheableAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.CachingAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.handler.EvictAnnotationHandler;
import io.github.davidhlp.spring.cache.redis.cache.RedisProCacheManager;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheRegister;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.cache.interceptor.BeanFactoryCacheOperationSourceAdvisor;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;

/** Redis缓存代理配置类 提供基于代理的Redis缓存注解驱动支持 */
@Configuration(proxyBeanMethods = false)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class RedisProxyCachingConfiguration {

    public static final String REDIS_CACHE_OPERATION_SOURCE_BEAN_NAME = "redisCacheOperationSource";

    @Bean(name = "redisCacheAdvisor")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public BeanFactoryCacheOperationSourceAdvisor redisCacheAdvisor(
            @Qualifier(REDIS_CACHE_OPERATION_SOURCE_BEAN_NAME)
                    CacheOperationSource redisCacheOperationSource,
            ResiCacheMethodInterceptor resiCacheMethodInterceptor) {
        BeanFactoryCacheOperationSourceAdvisor advisor =
                new BeanFactoryCacheOperationSourceAdvisor();
        advisor.setCacheOperationSource(redisCacheOperationSource);
        // Path C Step 5 — advisor advice 从 RedisCacheInterceptor 换成 ResiCacheMethodInterceptor
        advisor.setAdvice(resiCacheMethodInterceptor);
        advisor.setOrder(50);
        return advisor;
    }

    @Bean(name = REDIS_CACHE_OPERATION_SOURCE_BEAN_NAME)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public CacheOperationSource redisCacheOperationSource(
            RedisProCacheProperties redisProCacheProperties) {
        return new RedisCacheOperationSource(redisProCacheProperties.getNativeAnnotationMode());
    }

    /**
     * Path C Step 5 — 替换原 {@code redisCacheInterceptor} bean(创建
     * {@code RedisCacheInterceptor extends CacheInterceptor})为
     * {@code ResiCacheMethodInterceptor}(Step 5 临时 extends 老类,Step 7
     * 重写为独立实现)。Spring DI 自动注入全部依赖。
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public ResiCacheMethodInterceptor resiCacheMethodInterceptor(
            @Qualifier(REDIS_CACHE_OPERATION_SOURCE_BEAN_NAME)
                    CacheOperationSource redisCacheOperationSource,
            RedisProCacheManager cacheManager,
            KeyGenerator keyGenerator,
            RedisCacheRegister redisCacheRegister,
            CacheableAnnotationHandler cacheableAnnotationHandler,
            EvictAnnotationHandler evictAnnotationHandler,
            CachingAnnotationHandler cachingAnnotationHandler,
            CachePutAnnotationHandler cachePutAnnotationHandler,
            RedisProCacheProperties redisProCacheProperties) {

        return new ResiCacheMethodInterceptor(
                redisCacheOperationSource,
                cacheManager,
                keyGenerator,
                cacheableAnnotationHandler,
                evictAnnotationHandler,
                cachingAnnotationHandler,
                cachePutAnnotationHandler);
    }
}
