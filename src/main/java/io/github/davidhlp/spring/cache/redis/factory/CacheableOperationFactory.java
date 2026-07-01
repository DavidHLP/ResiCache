package io.github.davidhlp.spring.cache.redis.factory;

import io.github.davidhlp.spring.cache.redis.operation.RedisCacheAttributes;
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

    /**
     * 从 {@link RedisCacheAttributes} 构造 {@link RedisCacheableOperation} — 单一委派 seam
     * (ADR-0017)。
     *
     * <p>本方法在 ADR-0017 之前是 18 行 builder 链;现在退化为 1 行委派给
     * {@link RedisCacheableOperation#fromAttributes(java.lang.reflect.Method, String, RedisCacheAttributes)}。
     * Builder 字段映射的归属(Tell, Don't Ask)由 Operation 类的 static method 承担 — Operation
     * 最清楚自己的 Builder 该怎么填。
     */
    RedisCacheableOperation materialize(Method method, String key, RedisCacheAttributes a) {
        return RedisCacheableOperation.fromAttributes(method, key, a);
    }

    @Override
    protected Class<RedisCacheable> annotationClass() {
        return RedisCacheable.class;
    }
}
