package io.github.davidhlp.spring.cache.redis.core.factory;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.register.operation.RedisCachePutOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/** CachePut 操作工厂 负责创建 RedisCachePutOperation 对象 */
@Slf4j
@Component
public class CachePutOperationFactory
        implements OperationFactory<RedisCachePut, RedisCachePutOperation> {

    @Override
    public RedisCachePutOperation create(
            Method method, RedisCachePut annotation, Object target, Object[] args, String key) {
        String[] cacheNames = resolveCacheNames(annotation.cacheNames(), annotation.value());

        return RedisCachePutOperation.builder()
                .name(method.getName())
                .key(key)
                .ttl(annotation.ttl())
                .type(annotation.type())
                .cacheNullValues(annotation.cacheNullValues())
                .useBloomFilter(annotation.useBloomFilter())
                .expectedInsertions(annotation.expectedInsertions())
                .falseProbability(annotation.falseProbability())
                .sync(annotation.sync())
                .syncTimeout(annotation.syncTimeout())
                .randomTtl(annotation.randomTtl())
                .variance(annotation.variance())
                .enablePreRefresh(annotation.enablePreRefresh())
                .preRefreshThreshold(annotation.preRefreshThreshold())
                .preRefreshMode(annotation.preRefreshMode())
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
        return annotation instanceof RedisCachePut;
    }

    /** 解析缓存名称 优先使用 cacheNames，如果为空则使用 value */
    private String[] resolveCacheNames(String[] cacheNames, String[] values) {
        return (cacheNames != null && cacheNames.length > 0) ? cacheNames : values;
    }
}
