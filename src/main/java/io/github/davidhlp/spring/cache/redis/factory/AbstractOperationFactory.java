package io.github.davidhlp.spring.cache.redis.factory;

import io.github.davidhlp.spring.cache.redis.operation.RedisCacheAttributes;
import org.springframework.cache.interceptor.CacheOperation;

import java.lang.annotation.Annotation;

/**
 * 操作工厂模板基类，收敛三个具体工厂的结构性重复：
 * <ul>
 *   <li>{@link #supports(Annotation)} 的 instanceof 判定 —— 提升为 final，子类只提供 {@link #annotationClass()}</li>
 *   <li>{@code cacheNames/value} 优先逻辑 —— 收拢到
 *       {@link RedisCacheAttributesProjector#resolveCacheNames(String[], String[])}，
 *       三个具体 factory 现在只消费 {@link RedisCacheAttributes}，不再重复同样的 if-else</li>
 *   <li>{@code materialize(method, key, attributes)} 退化为单行委派给 Operation 类的
 *       {@code fromAttributes(method, key, attributes)} 静态方法(ADR-0017)— Builder 字段
 *       映射的归属在 Operation 自身,Factory 不再持有 18 行 builder 链</li>
 * </ul>
 *
 * <p>Builder 字段填充<strong>已下沉</strong>(ADR-0017):三个 Operation 类各自提供
 * {@code fromAttributes(method, key, attributes)} 静态方法,Builder 字段归属
 * (Tell, Don't Ask)落在最了解字段的 Operation 类。Factory 仅作为投影层
 * (RedisCacheAttributesProjector)→Operation 的路由层,本身不再持有 builder 链样板。
 *
 * @param <A> 注解类型
 * @param <O> 操作类型
 */
public abstract class AbstractOperationFactory<A extends Annotation, O extends CacheOperation>
        implements OperationFactory<A, O> {

    @Override
    public final boolean supports(Annotation annotation) {
        return annotationClass().isInstance(annotation);
    }

    /** 子类返回自己处理的注解类型，供 {@link #supports(Annotation)} 判定 */
    protected abstract Class<A> annotationClass();
}
