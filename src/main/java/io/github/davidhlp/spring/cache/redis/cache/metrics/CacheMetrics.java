package io.github.davidhlp.spring.cache.redis.cache.metrics;

/**
 * 缓存实例的指标快照值对象.
 *
 * <p>本 record 把 {@link io.github.davidhlp.spring.cache.redis.cache.RedisProCache} 内部的 7 个 Micrometer 指标(Timer × 3 + Counter × 4)
 * 收口到一个不可变快照对象,通过
 * {@link io.github.davidhlp.spring.cache.redis.cache.RedisProCache#metrics()} 单次读取。
 *
 * <p><b>收益</b>:
 * <ul>
 *   <li>调用方关心「这个 cache 的指标快照」语义而非多个独立 getter — deep 方法</li>
 *   <li>派生字段(hitRate)由 record 工厂方法计算,调用方不写算术</li>
 *   <li>Counter/Timer 字段由 {@code RedisProCacheTimers} 维护 null-safe 语义,
 *       本 record 工厂方法对 {@code null} Counter 返回 {@code 0L}</li>
 *   <li>新增指标(hit-ratio / 复合 timer 等)只在本 record 加字段 + 工厂方法加一行,
 *       不污染 RedisProCache public surface</li>
 * </ul>
 *
 * <p><b>接口是测试面</b>:本 record 工厂方法(无 Micrometer 依赖)是测试入口 —
 * 测试可直接 {@code new CacheMetrics(h, m, p, e)} 构造并断言 hitRate,无需 mock {@code Counter.count()}。
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
     * <p>算术集中在本方法内:调用方直接 {@code cache.metrics().hitRate()} 一行,
     * 无需做除法与零保护。
     *
     * @return 命中率,范围 [0.0, 1.0]
     */
    public double hitRate() {
        long total = hitCount + missCount;
        return total > 0 ? (double) hitCount / total : 0.0;
    }
}
