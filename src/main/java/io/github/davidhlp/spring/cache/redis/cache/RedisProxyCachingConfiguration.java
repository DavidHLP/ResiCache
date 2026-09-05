package io.github.davidhlp.spring.cache.redis.cache;

import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.cache.interceptor.BeanFactoryCacheOperationSourceAdvisor;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;

/**
 * Redis缓存代理配置类 提供基于代理的Redis缓存注解驱动支持。
 *
 * <p>装配顺序依赖(由 RedisProCacheConfigurationContractTest 行为探针钉住):
 * 本类在内部扫描中按类名字典序排在 {@code RedisProCacheConfiguration} 之后解析,
 * 因此 {@code @ConditionalOnBean(RedisProCacheManager.class)} 能看到已注册的
 * cacheManager 定义;用户 CacheManager 存在时库 cacheManager back-off,
 * advisor/interceptor 随之退场。若重命名本类打破该字典序,需改用显式顺序契约。
 */
@Configuration(proxyBeanMethods = false)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
class RedisProxyCachingConfiguration {

    public static final String REDIS_CACHE_OPERATION_SOURCE_BEAN_NAME = "redisCacheOperationSource";

    @Bean(name = "redisCacheAdvisor")
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    // 用户自定义 CacheManager → 库 cacheManager back-off → RedisProCacheManager
    // 不存在。advisor/interceptor 必须随之退场(用户自行接管 Spring Cache),
    // 否则启动期 UnsatisfiedDependency 直接失败(RM-005 探针发现)。
    @ConditionalOnBean(RedisProCacheManager.class)
    public BeanFactoryCacheOperationSourceAdvisor redisCacheAdvisor(
            @Qualifier(REDIS_CACHE_OPERATION_SOURCE_BEAN_NAME)
                    CacheOperationSource redisCacheOperationSource,
            RedisCacheInterceptor redisCacheInterceptor) {
        BeanFactoryCacheOperationSourceAdvisor advisor =
                new BeanFactoryCacheOperationSourceAdvisor();
        advisor.setCacheOperationSource(redisCacheOperationSource);
        // 单一 advice seam — advisor 直接持有 RedisCacheInterceptor
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
     * 单一 advice —— advisor 直接持有的拦截器,装配职责与拦截职责收口到同一处。
     *
     * <p>构造函数注入 {@link AnnotationChainEngine},由 Spring 自动装配
     * {@code List<AnnotationHandler>};链结构在 Engine 内部维护,本配置类零感知。
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnBean(RedisProCacheManager.class)
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
