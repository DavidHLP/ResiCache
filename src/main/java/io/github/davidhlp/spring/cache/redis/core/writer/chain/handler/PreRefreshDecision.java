package io.github.davidhlp.spring.cache.redis.core.writer.chain.handler;

/**
 * 预刷新决策
 * 
 * 由 PreRefreshHandler 返回，指示如何处理预刷新。
 */
public record PreRefreshDecision(
    /** 是否需要刷新 */
    boolean needsRefresh,
    
    /** 是否为同步刷新（true = 返回 miss 触发刷新；false = 异步后台刷新） */
    boolean isSync,
    
    /** 是否继续使用当前缓存值 */
    boolean continueWithCurrentValue
) {
    /** 不需要预刷新 */
    public static PreRefreshDecision noRefresh() {
        return new PreRefreshDecision(false, false, true);
    }
    
    /** 同步预刷新：返回 miss，触发调用方刷新数据 */
    public static PreRefreshDecision syncRefresh() {
        return new PreRefreshDecision(true, true, false);
    }
    
    /** 异步预刷新：后台刷新，返回当前值 */
    public static PreRefreshDecision asyncRefresh() {
        return new PreRefreshDecision(true, false, true);
    }
}
