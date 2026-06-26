package io.github.davidhlp.spring.cache.redis.factory;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheEvictOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/** CacheEvict 操作工厂，负责创建 RedisCacheEvictOperation 对象 */
@Slf4j
@Component
public class EvictOperationFactory extends AbstractOperationFactory<RedisCacheEvict, RedisCacheEvictOperation> {

    @Override
    public RedisCacheEvictOperation create(Method method, RedisCacheEvict annotation,
                                           Object target, Object[] args, String key) {
        String[] cacheNames = resolveCacheNames(annotation.cacheNames(), annotation.value());

        return RedisCacheEvictOperation.builder()
                .name(method.getName())
                .key(key)
                .cacheNames(cacheNames)
                .keyGenerator(annotation.keyGenerator())
                .cacheManager(annotation.cacheManager())
                .cacheResolver(annotation.cacheResolver())
                .condition(annotation.condition())
                .allEntries(annotation.allEntries())
                .beforeInvocation(annotation.beforeInvocation())
                .sync(annotation.sync())
                .syncTimeout(annotation.syncTimeout())
                .ttl(annotation.ttl())
                .useBloomFilter(annotation.useBloomFilter())
                .expectedInsertions(annotation.expectedInsertions())
                .falseProbability(annotation.falseProbability())
                .enableEarlyExpiration(annotation.enableEarlyExpiration())
                .earlyExpirationThreshold(annotation.earlyExpirationThreshold())
                .earlyExpirationMode(annotation.earlyExpirationMode())
                .build();
    }

    @Override
    protected Class<RedisCacheEvict> annotationClass() {
        return RedisCacheEvict.class;
    }
}
