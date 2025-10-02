package com.david.spring.cache.redis.config;

import com.david.spring.cache.redis.annotation.RedisCacheOperationSource;
import com.david.spring.cache.redis.core.RedisCacheInterceptor;
import com.david.spring.cache.redis.manager.RedisProCacheManager;
import com.david.spring.cache.redis.register.RedisCacheRegister;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.cache.interceptor.BeanFactoryCacheOperationSourceAdvisor;
import org.springframework.cache.interceptor.CacheInterceptor;
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
            CacheInterceptor redisCacheInterceptor) {
        BeanFactoryCacheOperationSourceAdvisor advisor =
                new BeanFactoryCacheOperationSourceAdvisor();
        advisor.setCacheOperationSource(redisCacheOperationSource);
        advisor.setAdvice(redisCacheInterceptor);
        advisor.setOrder(50); // 设置较高优先级，确保Redis缓存拦截器能够处理Redis注解
        return advisor;
    }

    @Bean(name = REDIS_CACHE_OPERATION_SOURCE_BEAN_NAME)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public CacheOperationSource redisCacheOperationSource() {
        return new RedisCacheOperationSource();
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public CacheInterceptor redisCacheInterceptor(
            @Qualifier(REDIS_CACHE_OPERATION_SOURCE_BEAN_NAME)
                    CacheOperationSource redisCacheOperationSource,
            RedisProCacheManager cacheManager,
            KeyGenerator keyGenerator,
            RedisCacheRegister redisCacheRegister) {

        // 创建带调试信息的 CacheInterceptor
        RedisCacheInterceptor interceptor = new RedisCacheInterceptor(redisCacheRegister);

        interceptor.setCacheOperationSource(redisCacheOperationSource);
        interceptor.setCacheManager(cacheManager);
        interceptor.setKeyGenerator(keyGenerator);
        interceptor.afterPropertiesSet();
        return interceptor;
    }
}
