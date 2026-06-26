package io.github.davidhlp.spring.cache.redis.factory;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/** Cacheable 操作工厂，负责创建 RedisCacheableOperation 对象 */
@Slf4j
@Component
public class CacheableOperationFactory extends AbstractOperationFactory<RedisCacheable, RedisCacheableOperation> {

    @Override
    public RedisCacheableOperation create(
            Method method, RedisCacheable annotation, Object target, Object[] args, String key) {
        String[] cacheNames = resolveCacheNames(annotation.cacheNames(), annotation.value());

        return RedisCacheableOperation.builder()
                .name(method.getName())
                .key(key)
                .ttl(annotation.ttl())
                .type(annotation.type())
                .cacheNullValues(annotation.cacheNullValues())
                .useBloomFilter(annotation.useBloomFilter())
                .expectedInsertions(annotation.expectedInsertions())
                .falseProbability(annotation.falseProbability())
                .randomTtl(annotation.randomTtl())
                .variance(annotation.variance())
                .enableEarlyExpiration(annotation.enableEarlyExpiration())
                .earlyExpirationThreshold(annotation.earlyExpirationThreshold())
                .earlyExpirationMode(annotation.earlyExpirationMode())
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
    protected Class<RedisCacheable> annotationClass() {
        return RedisCacheable.class;
    }
}
