package io.github.davidhlp.spring.cache.redis.config;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheOperationSource;
import io.github.davidhlp.spring.cache.redis.cache.RedisCacheInterceptor;
import io.github.davidhlp.spring.cache.redis.cache.RedisProCacheManager;
import io.github.davidhlp.spring.cache.redis.chain.metadata.MethodMetadataResolver;
import io.github.davidhlp.spring.cache.redis.annotation.handler.AnnotationChainEngine;

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
            RedisCacheInterceptor redisCacheInterceptor) {
        BeanFactoryCacheOperationSourceAdvisor advisor =
                new BeanFactoryCacheOperationSourceAdvisor();
        advisor.setCacheOperationSource(redisCacheOperationSource);
        // Path C 单一 advice seam — advisor 直接持有 RedisCacheInterceptor
        advisor.setAdvice(redisCacheInterceptor);
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
     * Path C 单一 advice —— advisor 直接持有的拦截器,装配职责与拦截职责收口到同一处
     * (原 Step 4/5/7 残骸 {@code CacheAspectSupportHelper}/{@code ResiCacheMethodInterceptor}
     * 已于本轮收敛删除,继承面 3 层 → 2 层,dead-injection 参数同步清理)。
     *
     * <p><strong>ADR-0013 链装配单一化</strong>:构造函数不再注入 4 个独立的
     * {@code *AnnotationHandler},改注入 {@link AnnotationChainEngine}。Engine
     * 由 Spring 自动装配 {@code List<AnnotationHandler>}(4 个 @Component handler),
     * 链结构在 Engine 内部维护,本配置类零感知。
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public RedisCacheInterceptor redisCacheInterceptor(
            @Qualifier(REDIS_CACHE_OPERATION_SOURCE_BEAN_NAME)
                    CacheOperationSource redisCacheOperationSource,
            RedisProCacheManager cacheManager,
            KeyGenerator keyGenerator,
            AnnotationChainEngine annotationChainEngine,
            MethodMetadataResolver methodMetadataResolver) {

        return new RedisCacheInterceptor(
                redisCacheOperationSource,
                cacheManager,
                keyGenerator,
                annotationChainEngine,
                methodMetadataResolver);
    }
}
