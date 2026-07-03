package io.github.davidhlp.spring.cache.redis.chain.model;

import io.github.davidhlp.spring.cache.redis.cache.CachedValue;
import org.springframework.lang.Nullable;

/**
 * 预取/提前过期决策 — {@link io.github.davidhlp.spring.cache.redis.protection.refresh.EarlyExpirationHandler}
 * 写入、由 {@link io.github.davidhlp.spring.cache.redis.chain.ActualCacheHandler} 读取的
 * 类型化跨 handler 消息(ADR-0036 / Round 26 C1).
 *
 * <p><b>替代 {@code CacheContext.attributes} 的 3 个业务 magic-string key</b>:
 * <ul>
 *   <li>{@code "earlyExpiration.skipped"} → {@link #earlyExpirationSkipped()}</li>
 *   <li>{@code "cache.prefetchedValue"} → {@link #prefetchedValue()}</li>
 *   <li>{@code "earlyExpiration.decision"} → {@link #decision()}</li>
 * </ul>
 * 三者皆是 {@code EarlyExpirationHandler} 同一次 GET 预取 + 判定的产物,生产者/消费者一一对应,
 * 收编为单一 record 消除跨 handler 字符串契约——与 ADR-0033 的 {@link TtlDecision} /
 * {@link NullDecision} 同构,是 CacheContext 类型化脉络的最后一对收官(原 attributes 袋
 * 业务 key 至此清零,仅保留 observer/bloom/lock 各模块自管的临时键).
 *
 * <p><b>字段关系(运行期典型,非编译期约束)</b>:
 * <ul>
 *   <li>生产者 {@code EarlyExpirationHandler.doHandle} 在缓存命中时构造完整三元组:
 *       {@code earlyExpirationSkipped} 由 {@code decision.needsRefresh() && decision.isSync()} 派生</li>
 *   <li>{@link #empty()} 表示 EarlyExpirationHandler 未触发(非 GET 或未启用),
 *       消费者 {@code ActualCacheHandler.handleGet} 据此回退原生 Redis GET</li>
 * </ul>
 *
 * @param earlyExpirationSkipped 是否跳过 ActualCacheHandler(同步提前过期,触发上层 miss 回源)
 * @param prefetchedValue        EarlyExpirationHandler 已预取的缓存值(供 ActualCacheHandler 复用,避免双重 GET)
 * @param decision               提前过期决策;{@code null} 表示未判定或未触发
 */
public record PrefetchDecision(
        boolean earlyExpirationSkipped,
        @Nullable CachedValue prefetchedValue,
        @Nullable EarlyExpirationDecision decision) {

    /** 无预取(EarlyExpirationHandler 未触发或缓存未命中)。 */
    public static PrefetchDecision empty() {
        return new PrefetchDecision(false, null, null);
    }

    /** 仅"跳过 ActualCacheHandler"的形态(供测试与 sync 跳过路径构造)。 */
    public static PrefetchDecision skipped() {
        return new PrefetchDecision(true, null, null);
    }

    /** 完整决策形态。 */
    public static PrefetchDecision of(boolean earlyExpirationSkipped,
                                      @Nullable CachedValue prefetchedValue,
                                      @Nullable EarlyExpirationDecision decision) {
        return new PrefetchDecision(earlyExpirationSkipped, prefetchedValue, decision);
    }
}
