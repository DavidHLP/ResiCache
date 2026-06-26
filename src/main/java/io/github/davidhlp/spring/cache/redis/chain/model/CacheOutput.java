package io.github.davidhlp.spring.cache.redis.chain.model;

import io.github.davidhlp.spring.cache.redis.chain.*;


import io.github.davidhlp.spring.cache.redis.chain.CacheResult;
import org.springframework.lang.Nullable;

/**
 * 缓存操作输出状态（可变）
 *
 * 每个 Handler 设置自己负责的字段：
 * - TtlHandler: finalTtl, shouldApplyTtl, ttlFromContext
 * - NullValueHandler: storeValue
 * - EarlyExpirationHandler: earlyExpirationCheckEnabled
 * - ActualCacheHandler: finalResult
 *
 * 字段分组，便于追踪状态变更来源。
 */
public class CacheOutput {

    // ==================== TTL Handler 输出 ====================

    /** 是否应该应用 TTL */
    private boolean shouldApplyTtl;

    /** 计算后的最终 TTL（秒） */
    private long finalTtl;

    /** 是否从上下文配置获取的 TTL */
    private boolean ttlFromContext;

    // ==================== NullValue Handler 输出 ====================

    /** 存储转换后的值（处理 null 值等） */
    @Nullable
    private Object storeValue;

    // ==================== EarlyExpiration Handler 输出 ====================

    /** 是否启用提前过期检查 */
    private boolean earlyExpirationCheckEnabled;

    // ==================== 控制标记 ====================

    /** 是否跳过后续处理器——SKIP_ALL 决策的物化状态，由引擎在遇到 SKIP_ALL 时单点设置。
     *  BloomFilterHandler.afterChainExecution 与引擎短路均读它。
     *  handler 内部不应读它判自身行为（它在 handler 返回后才生效）。 */
    private boolean skipRemaining = false;

    // ==================== CLEAN 操作专用 ====================

    /** 键模式（用于清理操作） */
    @Nullable
    private String keyPattern;

    // ==================== 最终结果 ====================

    /** 最终处理结果 */
    @Nullable
    private CacheResult finalResult;

    // ==================== Getters and Setters ====================

    // TTL 相关
    public boolean isShouldApplyTtl() { return shouldApplyTtl; }
    public void setShouldApplyTtl(boolean shouldApplyTtl) { this.shouldApplyTtl = shouldApplyTtl; }

    public long getFinalTtl() { return finalTtl; }
    public void setFinalTtl(long finalTtl) { this.finalTtl = finalTtl; }

    public boolean isTtlFromContext() { return ttlFromContext; }
    public void setTtlFromContext(boolean ttlFromContext) { this.ttlFromContext = ttlFromContext; }

    // NullValue 相关
    @Nullable
    public Object getStoreValue() { return storeValue; }
    public void setStoreValue(@Nullable Object storeValue) { this.storeValue = storeValue; }

    // EarlyExpiration 相关
    public boolean isEarlyExpirationCheckEnabled() { return earlyExpirationCheckEnabled; }
    public void setEarlyExpirationCheckEnabled(boolean earlyExpirationCheckEnabled) { this.earlyExpirationCheckEnabled = earlyExpirationCheckEnabled; }

    // 控制标记
    public boolean isSkipRemaining() { return skipRemaining; }
    public void markSkipRemaining() { this.skipRemaining = true; }

    // KeyPattern
    @Nullable
    public String getKeyPattern() { return keyPattern; }
    public void setKeyPattern(@Nullable String keyPattern) { this.keyPattern = keyPattern; }

    // 最终结果
    @Nullable
    public CacheResult getFinalResult() { return finalResult; }
    public void setFinalResult(@Nullable CacheResult finalResult) { this.finalResult = finalResult; }
}
