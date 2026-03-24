package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

import io.github.davidhlp.spring.cache.redis.core.writer.chain.CacheResult;
import org.springframework.lang.Nullable;

/**
 * 缓存操作输出状态（可变）
 * 
 * 每个 Handler 设置自己负责的字段：
 * - TtlHandler: finalTtl, shouldApplyTtl, ttlFromContext
 * - NullValueHandler: storeValue
 * - SyncLockHandler: lockContext
 * - PreRefreshHandler: preRefreshCheckEnabled, preRefreshDecision
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
    
    // ==================== PreRefresh Handler 输出 ====================
    
    /** 是否启用预刷新检查 */
    private boolean preRefreshCheckEnabled;
    
    /** 预刷新决策 */
    @Nullable
    private PreRefreshDecision preRefreshDecision;
    
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
    
    // PreRefresh 相关
    public boolean isPreRefreshCheckEnabled() { return preRefreshCheckEnabled; }
    public void setPreRefreshCheckEnabled(boolean preRefreshCheckEnabled) { this.preRefreshCheckEnabled = preRefreshCheckEnabled; }
    
    @Nullable
    public PreRefreshDecision getPreRefreshDecision() { return preRefreshDecision; }
    public void setPreRefreshDecision(@Nullable PreRefreshDecision preRefreshDecision) { this.preRefreshDecision = preRefreshDecision; }
    
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
