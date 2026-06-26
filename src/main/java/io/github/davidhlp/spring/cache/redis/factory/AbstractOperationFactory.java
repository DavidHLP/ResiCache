package io.github.davidhlp.spring.cache.redis.factory;

import org.springframework.cache.interceptor.CacheOperation;

import java.lang.annotation.Annotation;

/**
 * 操作工厂模板基类，收敛三个具体工厂的结构性重复：
 * <ul>
 *   <li>{@link #supports(Annotation)} 的 instanceof 判定 —— 提升为 final，子类只提供 {@link #annotationClass()}</li>
 *   <li>{@link #resolveCacheNames(String[], String[])} 的 cacheNames/value 优先逻辑 —— 三类工厂逐字相同，收敛为单一实现</li>
 * </ul>
 *
 * <p>Builder 字段填充<strong>不下沉</strong>：RedisCacheable/Put/EvictOperation 的 Builder 继承自不同的
 * Spring 基类（CacheableOperation.Builder / CachePutOperation.Builder / CacheEvictOperation.Builder），
 * 类型不兼容，无法用单一通用 Builder 填公共字段；子类各自实现 create() 填字段。
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

    /** 解析缓存名称：优先 cacheNames，为空则用 value。三类工厂逐字相同的逻辑，收敛于此。 */
    protected String[] resolveCacheNames(String[] cacheNames, String[] values) {
        return (cacheNames != null && cacheNames.length > 0) ? cacheNames : values;
    }
}
