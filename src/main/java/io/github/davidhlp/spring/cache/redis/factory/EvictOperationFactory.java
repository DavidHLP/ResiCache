package io.github.davidhlp.spring.cache.redis.factory;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheEvictOperation;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * CacheEvict 操作工厂，负责创建 {@link RedisCacheEvictOperation} 对象。
 *
 * <p><strong>Candidate A 投影层重构</strong>：本工厂现在<em>只消费</em>
 * {@link RedisCacheAttributes}，但 Evict 的 Builder 没有 {@code unless} / {@code type} /
 * {@code cacheNullValues} / {@code randomTtl} / {@code variance} 槽位——这些字段被本工厂
 * 显式忽略（不再应用到 Evict Builder），仅适用 Evict 独有的 {@code allEntries} /
 * {@code beforeInvocation} 与共享字段。
 */
@Slf4j
@Component
public class EvictOperationFactory
        extends AbstractOperationFactory<RedisCacheEvict, RedisCacheEvictOperation> {

    private final RedisCacheAttributesProjector projector;

    public EvictOperationFactory(RedisCacheAttributesProjector projector) {
        this.projector = projector;
    }

    @Override
    public RedisCacheEvictOperation create(
            Method method, RedisCacheEvict annotation, Object target, Object[] args, String key) {
        RedisCacheAttributes a = projector.from(annotation);
        return materialize(method, key, a);
    }

    /** 从 {@link RedisCacheAttributes} 构造 {@link RedisCacheEvictOperation}。 */
    RedisCacheEvictOperation materialize(Method method, String key, RedisCacheAttributes a) {
        return RedisCacheEvictOperation.builder()
                .name(method.getName())
                .key(key)
                .cacheNames(a.getCacheNames())
                .keyGenerator(a.getKeyGenerator())
                .cacheManager(a.getCacheManager())
                .cacheResolver(a.getCacheResolver())
                .condition(a.getCondition())
                .allEntries(a.isAllEntries())
                .beforeInvocation(a.isBeforeInvocation())
                .sync(a.isSync())
                .syncTimeout(a.getSyncTimeout())
                .ttl(a.getTtl())
                .useBloomFilter(a.isUseBloomFilter())
                .expectedInsertions(a.getExpectedInsertions())
                .falseProbability(a.getFalseProbability())
                .enableEarlyExpiration(a.isEnableEarlyExpiration())
                .earlyExpirationThreshold(a.getEarlyExpirationThreshold())
                .earlyExpirationMode(a.getEarlyExpirationMode())
                .build();
    }

    @Override
    protected Class<RedisCacheEvict> annotationClass() {
        return RedisCacheEvict.class;
    }
}
