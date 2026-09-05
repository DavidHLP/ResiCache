package io.github.davidhlp.spring.cache.redis.cache;




import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 布隆读侧穿透闸门 — 缓存穿透防护的「是否确定不存在」判定 seam.
 *
 * <p>此前该判定在两条读路径上重复且日志分叉:
 * <ul>
 *   <li>{@code BloomFilterHandler.handleGet}(责任链 GET):{@code !mightContain} → 记
 *       "rejected (key does not exist)" + {@code statistics.incMisses} + terminate miss</li>
 *   <li>{@code RedisProCache.isBloomShortCircuited}(loader 回源前):{@code !mightContain} →
 *       记 "rejected loader invocation" + {@code missCounter} 自增 + 短路返回 null</li>
 * </ul>
 * 两处对<em>同一个布隆过滤器</em>做同样的 {@code mightContain} 判定,却各写各的日志,判定逻辑
 * 若漂移(如一处忘了取反)不易发现。本闸门把「确定 miss 判定 + 统一 debug 日志」收口到唯一
 * 入口;各调用方仍各自记录自己体系的指标(链层 {@code CacheStatisticsCollector} vs
 * cache 层 Micrometer counter,两套指标服务不同用途,刻意不合并)与自己的短路控制流。
 *
 * <p>本闸门只承担<b>读侧</b>穿透判定;布隆的<b>写侧</b>回填 / 清空({@code add} / {@code clear})
 * 仍由 {@link BloomSupport} 直接承担(后置处理,不同关注点)。两个调用方
 * ({@code BloomFilterHandler} + {@code RedisProCache})共同支撑本 seam 的存在。
 */
@Slf4j
@Component
class BloomGate {

    private final BloomSupport bloomSupport;

    public BloomGate(BloomSupport bloomSupport) {
        this.bloomSupport = bloomSupport;
    }

    /**
     * 判定 key 是否「确定不在缓存中」(布隆返回 false → 一定不存在).
     *
     * <p>命中确定 miss 时记一次统一 debug 日志。调用方据返回值决定短路 + 自身指标。
     *
     * @param cacheName 缓存名
     * @param actualKey 去前缀的实际 key(与布隆回填 {@code add} 同源,避免键漂移)
     * @return true 表示布隆确定该 key 不存在(可短路);false 表示可能存在(继续)
     */
    public boolean definiteMiss(String cacheName, String actualKey) {
        if (bloomSupport.mightContain(cacheName, actualKey)) {
            return false;
        }
        log.debug("Bloom filter rejected (key does not exist): cacheName={}, key={}",
                cacheName, actualKey);
        return true;
    }
}
