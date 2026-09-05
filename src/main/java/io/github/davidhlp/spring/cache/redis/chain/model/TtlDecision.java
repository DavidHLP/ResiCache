package io.github.davidhlp.spring.cache.redis.chain.model;

/**
 * TTL 决策 — {@link io.github.davidhlp.spring.cache.redis.cache.TtlHandler}
 * 写入、由 {@link io.github.davidhlp.spring.cache.redis.cache.ActualCacheHandler} 读取的
 * 类型化跨 handler 消息。
 *
 * <p><b>不变式</b>：
 * <ul>
 *   <li>{@code shouldApplyTtl == true} ⇒ {@code finalTtl >= 0}（合法 TTL 秒数）</li>
 *   <li>{@code shouldApplyTtl == false} ⇒ {@code finalTtl == -1}（永久缓存标记）</li>
 * </ul>
 *
 * @param finalTtl       最终 TTL（秒）；{@code -1} 表示不应用 TTL（永久缓存）
 * @param shouldApplyTtl 是否应用 TTL
 */
public record TtlDecision(long finalTtl, boolean shouldApplyTtl) {

    /** 应用 TTL 的决策（合法 finalTtl）。 */
    public static TtlDecision applied(long finalTtl) {
        return new TtlDecision(finalTtl, true);
    }

    /** 跳过 TTL 的决策（永久缓存）。 */
    public static TtlDecision skipped() {
        return new TtlDecision(-1L, false);
    }
}