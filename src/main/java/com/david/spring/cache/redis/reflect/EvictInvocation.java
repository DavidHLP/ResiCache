package com.david.spring.cache.redis.reflect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.lang.reflect.Method;

/** 封装一次缓存驱逐（Evict）方法调用的上下文信息。 仅用于记录与注册，实际驱逐由 Spring Cache @CacheEvict 机制与 Cache 实现执行。 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class EvictInvocation {

    /** 目标 Bean（被 AOP 拦截的对象） */
    private Object targetBean;

    /** 目标方法 */
    private Method targetMethod;

    /** 调用参数 */
    private Object[] arguments;

    private EvictInvocationContext evictInvocationContext;

    @Builder
    public record EvictInvocationContext(
            String[] value,
            String[] cacheNames,
            String key,
            String keyGenerator,
            String cacheManager,
            String cacheResolver,
            String condition,
            boolean allEntries,
            boolean beforeInvocation,
            boolean sync) {}
}
