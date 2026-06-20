package io.github.davidhlp.spring.cache.redis.factory;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheEvictOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * CacheEvict 操作工厂
 * 负责创建 RedisCacheEvictOperation 对象
 */
@Slf4j
@Component
public class EvictOperationFactory implements OperationFactory<RedisCacheEvict, RedisCacheEvictOperation> {

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
    public boolean supports(Annotation annotation) {
        return annotation instanceof RedisCacheEvict;
    }

    /**
     * 解析缓存名称
     * 优先使用 cacheNames，如果为空则使用 value
     */
    private String[] resolveCacheNames(String[] cacheNames, String[] values) {
        return (cacheNames != null && cacheNames.length > 0) ? cacheNames : values;
    }
}
