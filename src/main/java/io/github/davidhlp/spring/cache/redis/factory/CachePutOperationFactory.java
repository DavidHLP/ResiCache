package io.github.davidhlp.spring.cache.redis.factory;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.operation.RedisCachePutOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * CachePut 操作工厂，负责创建 {@link RedisCachePutOperation} 对象。
 *
 * <p><strong>Candidate A 投影层重构</strong>：本工厂现在<em>只消费</em>
 * {@link RedisCacheAttributes}，与 {@link CacheableOperationFactory} 共用同一组
 * 默认值与字段语义——消除了原本与 {@code Cacheable} 18/18 字段逐字相同的死代码。
 *
 * <p>{@link RedisCacheAttributes} 由 {@link RedisCacheAttributesProjector}
 * 投影得到（{@code from(annotation)}），默认值的统一收敛在该投影器内完成。
 */
@Slf4j
@Component
public class CachePutOperationFactory
        extends AbstractOperationFactory<RedisCachePut, RedisCachePutOperation> {

    private final RedisCacheAttributesProjector projector;

    public CachePutOperationFactory(RedisCacheAttributesProjector projector) {
        this.projector = projector;
    }

    @Override
    public RedisCachePutOperation create(
            Method method, RedisCachePut annotation, Object target, Object[] args, String key) {
        RedisCacheAttributes a = projector.from(annotation);
        return materialize(method, key, a);
    }

    /** 从 {@link RedisCacheAttributes} 构造 {@link RedisCachePutOperation}。 */
    RedisCachePutOperation materialize(Method method, String key, RedisCacheAttributes a) {
        return RedisCachePutOperation.builder()
                .name(method.getName())
                .key(key)
                .cacheNames(a.getCacheNames())
                .keyGenerator(a.getKeyGenerator())
                .cacheManager(a.getCacheManager())
                .cacheResolver(a.getCacheResolver())
                .condition(a.getCondition())
                .unless(a.getUnless())
                .ttl(a.getTtl())
                .type(a.getType())
                .cacheNullValues(a.isCacheNullValues())
                .useBloomFilter(a.isUseBloomFilter())
                .expectedInsertions(a.getExpectedInsertions())
                .falseProbability(a.getFalseProbability())
                .randomTtl(a.isRandomTtl())
                .variance(a.getVariance())
                .enableEarlyExpiration(a.isEnableEarlyExpiration())
                .earlyExpirationThreshold(a.getEarlyExpirationThreshold())
                .earlyExpirationMode(a.getEarlyExpirationMode())
                .sync(a.isSync())
                .syncTimeout(a.getSyncTimeout())
                .build();
    }

    @Override
    protected Class<RedisCachePut> annotationClass() {
        return RedisCachePut.class;
    }
}
