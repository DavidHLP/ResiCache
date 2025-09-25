package com.david.spring.cache.redis.factory;

/**
 * 缓存类型枚举
 */
public enum CacheType {
    /**
     * 标准Redis缓存
     */
    REDIS,

    /**
     * 分层缓存（本地+Redis）
     */
    LAYERED,

    /**
     * 只读缓存
     */
    READ_ONLY,

    /**
     * 写入缓存
     */
    WRITE_THROUGH,

    /**
     * 异步缓存
     */
    ASYNC
}