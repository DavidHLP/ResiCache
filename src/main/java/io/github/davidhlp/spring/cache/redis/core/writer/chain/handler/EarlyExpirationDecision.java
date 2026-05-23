package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

/**
 * 提前过期决策
 * 
 * 由 EarlyExpirationHandler 返回，指示如何处理提前过期。
 */
public record EarlyExpirationDecision(
    /** 是否需要刷新 */
    boolean needsRefresh,
    
    /** 是否为同步刷新（true = 返回 miss 触发刷新；false = 异步后台刷新） */
    boolean isSync,
    
    /** 是否继续使用当前缓存值 */
    boolean continueWithCurrentValue
) {
    /** 不需要提前过期 */
    public static EarlyExpirationDecision noRefresh() {
        return new EarlyExpirationDecision(false, false, true);
    }
    
    /** 同步提前过期：返回 miss，触发调用方刷新数据 */
    public static EarlyExpirationDecision syncRefresh() {
        return new EarlyExpirationDecision(true, true, false);
    }
    
    /** 异步提前过期：后台刷新，返回当前值 */
    public static EarlyExpirationDecision asyncRefresh() {
        return new EarlyExpirationDecision(true, false, true);
    }
}
