package io.github.davidhlp.spring.cache.redis.holder;

import org.springframework.context.expression.AnnotatedElementKey;

import java.lang.reflect.Method;

/**
 * ThreadLocal 持有者，用于在 AOP Interceptor 与 CacheWriter 之间传递当前缓存操作的元数据标识。
 *
 * <p>由于 Spring Cache 的 {@code Cache.get(name, key)} 调用链路不携带方法上下文，
 * 而 ResiCache 的增强特性（BloomFilter、TTL 抖动、sync 等）都依赖于方法级别的注解元数据，
 * 因此通过 ThreadLocal 在拦截器入口设置 {@link AnnotatedElementKey}，在 Writer 层读取，
 * 是实现元数据查找与 Spring Cache 语义兼容的最小侵入方案。
 *
 * <p>使用规范：
 * <ul>
 *   <li>设置方（如 {@code RedisCacheInterceptor}）必须在 {@code try} 块中调用 {@code set}</li>
 *   <li>清理方必须在对应的 {@code finally} 块中调用 {@code clear}，防止线程池复用导致泄漏</li>
 *   <li>读取方（如 {@code RedisProCacheWriter}）应做好 null 容忍：若未设置则退化到默认行为</li>
 * </ul>
 */
public final class CacheOperationMetadataHolder {

    private static final ThreadLocal<AnnotatedElementKey> CURRENT_KEY = new ThreadLocal<>();

    private CacheOperationMetadataHolder() {
    }

    /**
     * 设置当前线程的缓存操作元数据键
     *
     * @param method      被拦截的方法
     * @param targetClass 目标类（原始类，非代理类）
     */
    public static void setCurrentKey(Method method, Class<?> targetClass) {
        CURRENT_KEY.set(new AnnotatedElementKey(method, targetClass));
    }

    /**
     * 获取当前线程的缓存操作元数据键
     *
     * @return AnnotatedElementKey，若未设置则返回 null
     */
    public static AnnotatedElementKey getCurrentKey() {
        return CURRENT_KEY.get();
    }

    /**
     * 清除当前线程的缓存操作元数据键
     */
    public static void clear() {
        CURRENT_KEY.remove();
    }
}
