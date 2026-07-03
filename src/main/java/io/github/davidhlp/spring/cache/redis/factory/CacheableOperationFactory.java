package io.github.davidhlp.spring.cache.redis.factory;

import io.github.davidhlp.spring.cache.redis.annotation.RedisCacheable;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheAttributes;
import io.github.davidhlp.spring.cache.redis.operation.RedisCacheableOperation;

import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Cacheable 操作工厂,负责创建 {@link RedisCacheableOperation} 对象。
 *
 * <p>只消费 {@link RedisCacheAttributes}(由 {@link RedisCacheAttributesProjector}
 * 投影),不再持有 builder 字段映射样板 —— 字段归属落在 Operation 自身的
 * {@code fromAttributes} 静态 seam(ADR-0017)。
 *
 * <p><b>ADR-0028 seam 收窄</b>:{@code create} 移除 implementation 未用的
 * {@code target}/{@code args};内联 1-liner {@code materialize}(直接委派
 * {@code fromAttributes});不再继承已删除的 {@code AbstractOperationFactory}
 * (其 {@code supports}/{@code annotationClass} 死链一并清理);移除未使用的
 * {@code @Slf4j}。
 */
@Component
public class CacheableOperationFactory
        implements OperationFactory<RedisCacheable, RedisCacheableOperation> {

    private final RedisCacheAttributesProjector projector;

    public CacheableOperationFactory(RedisCacheAttributesProjector projector) {
        this.projector = projector;
    }

    @Override
    public RedisCacheableOperation create(Method method, RedisCacheable annotation, String key) {
        RedisCacheAttributes a = projector.from(annotation);
        return RedisCacheableOperation.fromAttributes(method, key, a);
    }
}
