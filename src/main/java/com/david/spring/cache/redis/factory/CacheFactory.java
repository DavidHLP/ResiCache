package com.david.spring.cache.redis.factory;

import org.springframework.cache.Cache;

/**
 * 缓存工厂接口
 * 使用工厂模式来创建不同类型的缓存实例
 */
public interface CacheFactory {

    /**
     * 创建缓存实例
     *
     * @param config 缓存配置
     * @return 缓存实例
     */
    Cache createCache(CacheCreationConfig config);

    /**
     * 判断工厂是否支持指定的缓存类型
     *
     * @param cacheType 缓存类型
     * @return 是否支持
     */
    boolean supports(CacheType cacheType);

    /**
     * 获取工厂优先级，数值越小优先级越高
     *
     * @return 优先级
     */
    default int getOrder() {
        return 0;
    }
}