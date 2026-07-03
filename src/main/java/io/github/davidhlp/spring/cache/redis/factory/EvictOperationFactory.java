package io.github.davidhlp.spring.cache.redis.factory;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheEvict;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheAttributes;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheEvictOperation;

import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * CacheEvict 操作工厂,负责创建 {@link RedisCacheEvictOperation} 对象。
 *
 * <p>只消费 {@link RedisCacheAttributes}(由 {@link RedisCacheAttributesProjector}
 * 投影);Evict 字段集是 Cacheable/Put 的子集 + Evict-only 字段
 * ({@code allEntries}/{@code beforeInvocation}),由
 * {@link RedisCacheEvictOperation#fromAttributes} 自行决定子集与排除规则(ADR-0017)。
 *
 * <p><b>ADR-0028 seam 收窄</b>:见 {@link CacheableOperationFactory} —— 同步移除
 * {@code target}/{@code args}、内联 {@code materialize}、改继承为实现、移除
 * 未使用的 {@code @Slf4j}。
 */
@Component
public class EvictOperationFactory
        implements OperationFactory<RedisCacheEvict, RedisCacheEvictOperation> {

    private final RedisCacheAttributesProjector projector;

    public EvictOperationFactory(RedisCacheAttributesProjector projector) {
        this.projector = projector;
    }

    @Override
    public RedisCacheEvictOperation create(Method method, RedisCacheEvict annotation, String key) {
        RedisCacheAttributes a = projector.from(annotation);
        return RedisCacheEvictOperation.fromAttributes(method, key, a);
    }
}
