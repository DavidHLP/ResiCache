package io.github.davidhlp.spring.cache.redis.factory;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCachePut;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheAttributes;
import io.github.davidhlp.spring.cache.redis.operation.RedisCachePutOperation;

import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * CachePut 操作工厂,负责创建 {@link RedisCachePutOperation} 对象。
 *
 * <p>只消费 {@link RedisCacheAttributes}(由 {@link RedisCacheAttributesProjector}
 * 投影),字段归属落在 {@link RedisCachePutOperation#fromAttributes} 静态 seam(ADR-0017)。
 *
 * <p><b>ADR-0028 seam 收窄</b>:见 {@link CacheableOperationFactory} —— 同步移除
 * {@code target}/{@code args}、内联 {@code materialize}、改继承为实现、移除
 * 未使用的 {@code @Slf4j}。
 */
@Component
public class CachePutOperationFactory
        implements OperationFactory<RedisCachePut, RedisCachePutOperation> {

    private final RedisCacheAttributesProjector projector;

    public CachePutOperationFactory(RedisCacheAttributesProjector projector) {
        this.projector = projector;
    }

    @Override
    public RedisCachePutOperation create(Method method, RedisCachePut annotation, String key) {
        RedisCacheAttributes a = projector.from(annotation);
        return RedisCachePutOperation.fromAttributes(method, key, a);
    }
}
