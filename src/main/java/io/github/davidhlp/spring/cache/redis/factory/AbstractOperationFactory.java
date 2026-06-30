package io.github.davidhlp.spring.cache.redis.factory;

import org.springframework.cache.interceptor.CacheOperation;

import java.lang.annotation.Annotation;

/**
 * 操作工厂模板基类，收敛三个具体工厂的结构性重复：
 * <ul>
 *   <li>{@link #supports(Annotation)} 的 instanceof 判定 —— 提升为 final，子类只提供 {@link #annotationClass()}</li>
 *   <li>{@code cacheNames/value} 优先逻辑 —— 收拢到
 *       {@link RedisCacheAttributesProjector#resolveCacheNames(String[], String[])}，
 *       三个具体 factory 现在只消费 {@link RedisCacheAttributes}，不再重复同样的 if-else</li>
 * </ul>
 *
 * <p>Builder 字段填充<strong>不下沉</strong>：RedisCacheable/Put/EvictOperation 的 Builder 继承自不同的
 * Spring 基类（CacheableOperation.Builder / CachePutOperation.Builder / CacheEvictOperation.Builder），
 * 类型不兼容，无法用单一通用 Builder 填公共字段；子类各自实现 {@code materialize(...)} 填字段。
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
