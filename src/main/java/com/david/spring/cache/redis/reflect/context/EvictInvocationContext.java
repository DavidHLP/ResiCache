package com.david.spring.cache.redis.reflect.context;

import com.david.spring.cache.redis.reflect.interfaces.InvocationContext;
import lombok.Builder;
import org.springframework.lang.Nullable;

/**
 * 缓存驱逐上下文实现类
 * 主人，这个类实现了驱逐操作的上下文接口喵~
 */
@Builder
public record EvictInvocationContext(
        /* 缓存名称别名 */
        String[] value,
        /* 缓存名称 */
        String[] cacheNames,
        /* 缓存键表达式 */
        String key,
        /* KeyGenerator Bean 名称 */
        String keyGenerator,
        /* CacheManager Bean 名称 */
        String cacheManager,
        /* CacheResolver Bean 名称 */
        String cacheResolver,
        /* 条件表达式 */
        String condition,
        /* 是否清除所有条目 */
        boolean allEntries,
        /* 是否在方法调用前执行清除 */
        boolean beforeInvocation,
        /* 是否同步执行 */
        boolean sync) implements InvocationContext {

    @Override
    public String[] getCacheNames() {
        return cacheNames != null ? cacheNames : (value != null ? value : new String[0]);
    }

    @Override
    @Nullable
    public String getKey() {
        return key;
    }

    @Override
    @Nullable
    public String getCondition() {
        return condition;
    }

    @Override
    @Nullable
    public String getKeyGenerator() {
        return keyGenerator;
    }

    @Override
    @Nullable
    public String getCacheManager() {
        return cacheManager;
    }

    @Override
    @Nullable
    public String getCacheResolver() {
        return cacheResolver;
    }

    @Override
    public boolean isSync() {
        return sync;
    }

    @Override
    public String getContextType() {
        return "EvictInvocation";
    }
    
    /**
     * 是否清除所有条目（Evict特有）
     * @return 是否清除所有条目
     */
    public boolean isAllEntries() {
        return allEntries;
    }
    
    /**
     * 是否在方法调用前执行清除（Evict特有）
     * @return 是否在方法调用前执行清除
     */
    public boolean isBeforeInvocation() {
        return beforeInvocation;
    }
}
