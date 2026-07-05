package io.github.davidhlp.spring.cache.redis.cache;

/**
 * 缓存实例的指标快照值对象 — ADR-0047 / C2 收敛.
 *
 * <p>本 record 把 {@link RedisProCache} 内部的 7 个 Micrometer 指标(Timer × 3 + Counter × 4)
 * 收口到一个不可变快照对象,通过 {@link RedisProCache#metrics()} 单次读取。
 *
 * <p><b>动机</b>:原设计暴露 5 个 {@code getXCount()} 委托方法 + 1 个
 * {@code getHitRate()} 派生方法,接口与实现等宽(浅模块)。每次读取都走
 * {@code field != null ? field.count() : 0L} 样板 — 5 处重复。{@code getHitRate}
 * 再调两个 getter 做除法,分散在调用方做算术。
 *
 * <p><b>收益</b>:
 * <ul>
 *   <li>1 个 deep 方法替代 5 个 thin getter — 收敛后的接口用调用方关心
 *       「这个 cache 的指标快照」语义而非 5 个独立的 getter</li>
 *   <li>派生字段(hitRate)由 record 工厂方法计算,调用方不再写算术</li>
 *   <li>Counter/Timer 字段仍由 {@link RedisProCacheTimers} 维护 null-safe 语义,
 *       本 record 工厂方法对 {@code null} Counter 返回 {@code 0L} 与原 getter 等价</li>
 *   <li>未来若新增指标(hit-ratio / 复合 timer 等)只在本 record 加字段 + 工厂方法
 *       加一行,不再污染 RedisProCache public surface</li>
 * </ul>
 *
 * <p><b>接口是测试面</b>:本 record 工厂方法(无 Micrometer 依赖)是测试入口 —
 * 测试可以直接 {@code new CacheMetrics(h, m, p, e, 0)} 构造并断言 hitRate,
 * 不再需要 mock {@code Counter.count()}。
 *
 * @param hitCount    命中次数
 * @param missCount   未命中次数
 * @param putCount    写入次数
 * @param evictCount  淘汰次数
 */
public record CacheMetrics(
        long hitCount,
        long missCount,
        long putCount,
        long evictCount) {

    /**
     * 命中率 = {@code hitCount / (hitCount + missCount)};无请求时返回 {@code 0.0}。
     *
     * <p>原 {@link RedisProCache#getHitRate()} 的算术在本方法内集中:调用方直接
     * {@code cache.metrics().hitRate()} 一行,无需做除法与零保护。
     *
     * @return 命中率,范围 [0.0, 1.0]
     */
    public double hitRate() {
        long total = hitCount + missCount;
        return total > 0 ? (double) hitCount / total : 0.0;
    }
}