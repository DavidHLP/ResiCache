package com.david.spring.cache.redis.event;

/**
 * 缓存事件类型
 */
public enum CacheEventType {
    /**
     * 缓存命中
     */
    CACHE_HIT,

    /**
     * 缓存未命中
     */
    CACHE_MISS,

    /**
     * 缓存写入
     */
    CACHE_PUT,

    /**
     * 缓存删除
     */
    CACHE_EVICT,

    /**
     * 缓存清空
     */
    CACHE_CLEAR,

    /**
     * 缓存过期
     */
    CACHE_EXPIRED,

    /**
     * 预刷新触发
     */
    PRE_REFRESH_TRIGGERED,

    /**
     * 预刷新完成
     */
    PRE_REFRESH_COMPLETED,

    /**
     * 缓存操作开始
     */
    CACHE_OPERATION_START,

    /**
     * 缓存操作结束
     */
    CACHE_OPERATION_END,

    /**
     * 缓存错误
     */
    CACHE_ERROR
}