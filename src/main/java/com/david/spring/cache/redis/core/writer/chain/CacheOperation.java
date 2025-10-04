package com.david.spring.cache.redis.core.writer.chain;

/** 缓存操作类型枚举 */
public enum CacheOperation {
    /** 获取缓存 */
    GET,
    /** 写入缓存 */
    PUT,
    /** 条件写入缓存（不存在时才写入） */
    PUT_IF_ABSENT,
    /** 删除缓存 */
    REMOVE,
    /** 清空缓存 */
    CLEAN
}
