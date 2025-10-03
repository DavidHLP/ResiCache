package com.david.spring.cache.redis.core.writer.handler;

import com.david.spring.cache.redis.register.operation.RedisCacheableOperation;
import lombok.Builder;
import lombok.Data;
import org.springframework.lang.Nullable;

import java.time.Duration;

/** 缓存操作上下文，贯穿整个责任链 */
@Data
@Builder
public class CacheContext {
    /** 缓存操作类型 */
    private CacheOperation operation;

    /** 缓存名称 */
    private String cacheName;

    /** Redis 完整 key */
    private String redisKey;

    /** 实际 key（去除前缀） */
    private String actualKey;

    /** 缓存值（字节数组） */
    private byte[] valueBytes;

    /** 反序列化后的值 */
    private Object deserializedValue;

    /** TTL */
    @Nullable private Duration ttl;

    /** 缓存操作配置 */
    @Nullable private RedisCacheableOperation cacheOperation;

    /** 是否应该应用 TTL */
    private boolean shouldApplyTtl;

    /** 计算后的最终 TTL（秒） */
    private long finalTtl;

    /** 是否从上下文配置获取的 TTL */
    private boolean ttlFromContext;

    /** 键模式（用于清理操作） */
    private String keyPattern;

    /** 是否跳过后续处理器 */
    private boolean skipRemaining;

    /** 存储转换后的值（处理 null 值等） */
    private Object storeValue;
}
