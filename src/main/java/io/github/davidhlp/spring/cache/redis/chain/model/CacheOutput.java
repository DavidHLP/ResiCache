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
 * - SyncLockHandler: lockContext
 * - EarlyExpirationHandler: earlyExpirationCheckEnabled, earlyExpirationDecision
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
    
    // ==================== SyncLock Handler 输出 ====================
    
    /** 锁上下文 */
    @Nullable
    private LockContext lockContext;
    
    // ==================== EarlyExpiration Handler 输出 ====================
    
    /** 是否启用提前过期检查 */
    private boolean earlyExpirationCheckEnabled;
    
    /** 提前过期决策 */
    @Nullable
    private EarlyExpirationDecision earlyExpirationDecision;
    
    // ==================== 控制标记 ====================
    
    /** 是否跳过后续处理器 */
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
    
    // Lock 相关
    @Nullable
    public LockContext getLockContext() { return lockContext; }
    public void setLockContext(@Nullable LockContext lockContext) { this.lockContext = lockContext; }
    
    // EarlyExpiration 相关
    public boolean isEarlyExpirationCheckEnabled() { return earlyExpirationCheckEnabled; }
    public void setEarlyExpirationCheckEnabled(boolean earlyExpirationCheckEnabled) { this.earlyExpirationCheckEnabled = earlyExpirationCheckEnabled; }
    
    @Nullable
    public EarlyExpirationDecision getEarlyExpirationDecision() { return earlyExpirationDecision; }
    public void setEarlyExpirationDecision(@Nullable EarlyExpirationDecision earlyExpirationDecision) { this.earlyExpirationDecision = earlyExpirationDecision; }
    
    // 控制标记
    public boolean isSkipRemaining() { return skipRemaining; }
    public void markSkipRemaining() { this.skipRemaining = true; }
    public void clearSkipRemaining() { this.skipRemaining = false; }
    
    // KeyPattern
    @Nullable
    public String getKeyPattern() { return keyPattern; }
    public void setKeyPattern(@Nullable String keyPattern) { this.keyPattern = keyPattern; }
    
    // 最终结果
    @Nullable
    public CacheResult getFinalResult() { return finalResult; }
    public void setFinalResult(@Nullable CacheResult finalResult) { this.finalResult = finalResult; }
}
