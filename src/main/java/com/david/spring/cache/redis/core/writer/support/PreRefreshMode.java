package com.david.spring.cache.redis.core.writer.support;

/**
 * 预刷新模式
 */
public enum PreRefreshMode {
    /**
     * 同步预刷新模式
     * 当缓存接近过期时，返回 null 触发缓存未命中，让调用者重新加载数据
     * 优点：逻辑简单，确保数据立即刷新
     * 缺点：用户需要等待数据重新加载
     */
    SYNC,

    /**
     * 异步预刷新模式
     * 当缓存接近过期时，返回旧值给用户，同时在后台异步刷新缓存
     * 优点：用户体验好，无需等待
     * 缺点：实现复杂，需要在拦截器层面支持
     */
    ASYNC
}
