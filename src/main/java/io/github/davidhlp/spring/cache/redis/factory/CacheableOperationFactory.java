package io.github.davidhlp.spring.cache.redis.factory;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Cacheable 操作工厂，负责创建 {@link RedisCacheableOperation} 对象。
 *
 * <p><strong>Candidate A 投影层重构</strong>：本工厂现在<em>只消费</em>
 * {@link RedisCacheAttributes}，不再直接读注解字段——这样消除了原"18/18 builder 调用
 * 逐字重复（{@code Cacheable ≡ Put}）"和"3 处默认值漂移"的死代码与认知负担。
 *
 * <p>{@link RedisCacheAttributes} 由 {@link RedisCacheAttributesProjector}
 * 投影得到（{@code from(annotation)}），默认值的统一收敛在该投影器内完成。
 */
@Slf4j
@Component
public class CacheableOperationFactory
        extends AbstractOperationFactory<RedisCacheable, RedisCacheableOperation> {

    private final RedisCacheAttributesProjector projector;

    public CacheableOperationFactory(RedisCacheAttributesProjector projector) {
        this.projector = projector;
    }

    @Override
    public RedisCacheableOperation create(
            Method method, RedisCacheable annotation, Object target, Object[] args, String key) {
        RedisCacheAttributes a = projector.from(annotation);
        return materialize(method, key, a);
    }

    /** 从 {@link RedisCacheAttributes} 构造 {@link RedisCacheableOperation}。 */
    RedisCacheableOperation materialize(Method method, String key, RedisCacheAttributes a) {
        return RedisCacheableOperation.builder()
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
                .expectedInsertions((int) Math.min(Integer.MAX_VALUE, Math.max(0L, a.getExpectedInsertions())))
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
    protected Class<RedisCacheable> annotationClass() {
        return RedisCacheable.class;
    }
}
