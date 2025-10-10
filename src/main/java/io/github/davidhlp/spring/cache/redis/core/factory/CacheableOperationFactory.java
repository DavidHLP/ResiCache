package io.github.davidhlp.spring.cache.redis.core.factory;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCacheableOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/** Cacheable 操作工厂 负责创建 RedisCacheableOperation 对象 */
@Slf4j
@Component
public class CacheableOperationFactory
        implements OperationFactory<RedisCacheable, RedisCacheableOperation> {

    @Override
    public RedisCacheableOperation create(
            Method method, RedisCacheable annotation, Object target, Object[] args, String key) {
        String[] cacheNames = resolveCacheNames(annotation.cacheNames(), annotation.value());

        return RedisCacheableOperation.builder()
                .name(method.getName())
                .key(key)
                .ttl(annotation.ttl())
                .type(annotation.type())
                .useSecondLevelCache(annotation.useSecondLevelCache())
                .cacheNullValues(annotation.cacheNullValues())
                .useBloomFilter(annotation.useBloomFilter())
                .randomTtl(annotation.randomTtl())
                .variance(annotation.variance())
                .enablePreRefresh(annotation.enablePreRefresh())
                .preRefreshThreshold(annotation.preRefreshThreshold())
                .preRefreshMode(annotation.preRefreshMode())
                .sync(annotation.sync())
                .syncTimeout(annotation.syncTimeout())
                .cacheManager(annotation.cacheManager())
                .cacheResolver(annotation.cacheResolver())
                .condition(annotation.condition())
                .keyGenerator(annotation.keyGenerator())
                .unless(annotation.unless())
                .cacheNames(cacheNames)
                .build();
    }

    @Override
    public boolean supports(Annotation annotation) {
        return annotation instanceof RedisCacheable;
    }

    /** 解析缓存名称 优先使用 cacheNames，如果为空则使用 value */
    private String[] resolveCacheNames(String[] cacheNames, String[] values) {
        return (cacheNames != null && cacheNames.length > 0) ? cacheNames : values;
    }
}
