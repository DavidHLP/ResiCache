package com.david.spring.cache.redis.reflect.interfaces;

import org.springframework.lang.Nullable;

/**
 * 缓存调用上下文接口，定义所有缓存操作的通用属性
 * 主人，这个接口提取了所有缓存操作的公共属性喵~
 */
public interface InvocationContext {
    
    /**
     * 获取缓存名称数组
     * @return 缓存名称数组
     */
    String[] getCacheNames();
    
    /**
     * 获取缓存键表达式
     * @return 缓存键表达式
     */
    @Nullable
    String getKey();
    
    /**
     * 获取条件表达式
     * @return 条件表达式
     */
    @Nullable
    String getCondition();
    
    /**
     * 获取 KeyGenerator Bean 名称
     * @return KeyGenerator Bean 名称
     */
    @Nullable
    String getKeyGenerator();
    
    /**
     * 获取 CacheManager Bean 名称
     * @return CacheManager Bean 名称
     */
    @Nullable
    String getCacheManager();
    
    /**
     * 获取 CacheResolver Bean 名称
     * @return CacheResolver Bean 名称
     */
    @Nullable
    String getCacheResolver();
    
    /**
     * 是否同步执行
     * @return 是否同步执行
     */
    boolean isSync();
    
    /**
     * 获取上下文类型，用于日志和调试
     * @return 上下文类型名称
     */
    String getContextType();
}
