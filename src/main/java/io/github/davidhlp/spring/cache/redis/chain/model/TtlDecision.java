package io.github.davidhlp.spring.cache.redis.chain.model;

/**
 * TTL 决策 — {@link io.github.davidhlp.spring.cache.redis.protection.avalanche.TtlHandler}
 * 写入、由 {@link io.github.davidhlp.spring.cache.redis.chain.handler.ActualCacheHandler} 读取的
 * 类型化跨 handler 消息。
 *
 * <p><b>ADR-0033 替代 {@code CacheOutput.shouldApplyTtl}/{@code finalTtl}/{@code ttlFromContext}
 * 三字段共享袋</b>:
 * <ul>
 *   <li>原设计：handler 把 3 个字段写进 {@code CacheOutput}（mutable bean），下游 handler
 *       通过 {@code context.getOutput().getXxx()} 读 — 字段分散、缺少类型约束、所有 handler
 *       都看得到所有字段（"耦合跨 seam"）</li>
 *   <li>新设计：本 record 由唯一生产者 {@code TtlHandler} 写入、由唯一消费者
 *       {@code ActualCacheHandler.handlePut/handlePutIfAbsent} 读取，类型约束 + locality
 *       双收</li>
 *   <li>{@code ttlFromContext} 字段被删除（main code 全项目 0 reader，test 5 处断言随之删）</li>
 * </ul>
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