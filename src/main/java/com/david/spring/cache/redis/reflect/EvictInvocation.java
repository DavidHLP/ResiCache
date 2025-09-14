package com.david.spring.cache.redis.reflect;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.lang.reflect.Method;

/**
 * 封装一次缓存驱逐（Evict）方法调用的上下文信息。
 * 仅用于记录与注册，实际驱逐由 Spring Cache @CacheEvict 机制与 Cache 实现执行。
 */
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

    /** 相关缓存名（合并 value 与 cacheNames） */
    private String[] cacheNames;

    /** 解析后的 key（可能为 null，当 allEntries=true 时无具体 key） */
    private Object key;

    /** 是否驱逐所有条目 */
    private boolean allEntries;

    /** 是否在方法执行前驱逐 */
    private boolean beforeInvocation;

    /** 条件表达式（原样保存，便于日志或后续扩展） */
    private String condition;

    /** 是否同步语义（与注解属性保持对齐，记录用） */
    private boolean sync;
}
