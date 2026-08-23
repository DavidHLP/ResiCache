package io.github.davidhlp.spring.cache.redis.protection.bloom;

import io.github.davidhlp.spring.cache.redis.protection.bloom.filter.BloomIFilter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Bloom 过滤器统一入口。
 *
 * <p><b>本类职责</b>:filter 代理 + fail-open 策略。
 * <ul>
 *   <li>{@link #mightContain}:rebuilding 窗口期内 fail-open(true);否则代理给 {@link BloomIFilter}
 *       并在底层异常时 fail-open</li>
 *   <li>{@link #add}:代理给 {@link BloomIFilter};底层异常仅记录日志不抛出</li>
 *   <li>{@link #clear}:代理给 {@link BloomIFilter};即使底层 clear 抛异常也尝试
 *       {@link BloomRebuilder#markRebuilding} 开窗(最大程度保护 fail-open 语义)</li>
 * </ul>
 *
 * <p><b>Rebuilding 窗口背景</b>:{@link #clear} 清空过滤器后,空布隆对所有 key
 * 判定 {@code mightContain=false},导致后续 GET 在 {@code RedisProCache.get(key, loader)}
 * 的前置短路处<b>静默返回 null</b>(既不查缓存也不调 loader)—— 违反 Spring
 * {@code @Cacheable}"miss 即调 loader 返回真实值"的契约,是数据正确性缺陷。rebuilding 窗口
 * 用短暂的 fail-open 把请求导向 loader,由 PUT 回填重建布隆,窗口由 Redis TTL 自动结束。
 *
 * <p><b>Cluster 一致性</b>:rebuilding 标志存于 Redis(而非仅 local),保证多实例一致;
 * 本地 Caffeine 短缓存(1s)在 {@link BloomRebuilder} 内部管理,容忍秒级跨实例不一致。
 *
 * @see BloomRebuilder
 * @see io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties.BloomFilterProperties#getRebuildWindowSeconds()
 */
@Slf4j
@Component
public class BloomSupport {

    private final BloomIFilter bloomIFilter;
    private final BloomRebuilder rebuilder;

    /**
     * @param bloomIFilter 底层布隆过滤器(Hierarchical: local + redis)
     * @param rebuilder     rebuilding 窗口状态机
     */
    @Autowired
    public BloomSupport(BloomIFilter bloomIFilter, @Nullable BloomRebuilder rebuilder) {
        this.bloomIFilter = bloomIFilter;
        this.rebuilder = rebuilder;
    }

    /**
     * 判断缓存是否可能存在指定键 —— 单一 fail-open 判定 seam。
     *
     * <p>三级短路(均 fail-open 或保底):
     * <ol>
     *   <li>rebuilding 窗口期 → true(避免 CLEAN 后静默 null 违反 @Cacheable 契约)</li>
     *   <li>代理给底层 {@link BloomIFilter#mightContain}</li>
     *   <li>底层异常 → true(避免误拒绝)</li>
     * </ol>
     *
     * @param cacheName 缓存名
     * @param key       键(已由 {@link io.github.davidhlp.spring.cache.redis.cache.model.CacheKeys#bloomKey()}
     *                  派生为 actualKey 形态,与 PUT 时 add 同源)
     * @return 是否可能存在
     */
    public boolean mightContain(final String cacheName, final String key) {
        if (rebuilder != null && rebuilder.isRebuilding(cacheName)) {
            log.debug("Bloom rebuilding window active (fail-open): cacheName={}", cacheName);
            return true;
        }
        try {
            return bloomIFilter.mightContain(cacheName, key);
        } catch (Exception ex) {
            log.error("Bloom filter mightContain failed, defaulting to may-contain: cacheName={}, key={}",
                    cacheName, key, ex);
            return true;
        }
    }

    /**
     * 将指定键加入 Bloom 过滤器 —— 单一代理 seam。
     *
     * <p>底层异常时仅记录日志,不抛出(避免缓存写入路径被布隆异常污染)。
     *
     * @param cacheName 缓存名
     * @param key       键(已派生的 actualKey 形态)
     */
    public void add(final String cacheName, final String key) {
        try {
            bloomIFilter.add(cacheName, key);
        } catch (Exception ex) {
            log.error("Bloom filter add failed: cacheName={}, key={}", cacheName, key, ex);
        }
    }

    /**
     * 清空指定缓存对应的 Bloom 过滤器,并开启 rebuilding 窗口。
     *
     * <p>即使底层 clear 抛异常,仍尝试开启 rebuilding 窗口(标志是 Redis 独立操作),
     * 以最大程度保护 fail-open 语义;{@link BloomRebuilder} 内部标志写入失败也不抛出
     * (退化为无窗口旧行为)。
     *
     * @param cacheName 缓存名
     */
    public void clear(final String cacheName) {
        try {
            bloomIFilter.clear(cacheName);
        } catch (Exception ex) {
            log.error("Bloom filter clear failed: cacheName={}", cacheName, ex);
        }
        if (rebuilder != null) {
            rebuilder.markRebuilding(cacheName);
        }
    }
}
