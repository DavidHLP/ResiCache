package io.github.davidhlp.spring.cache.redis.cache;





import io.github.davidhlp.spring.cache.redis.protection.bloom.filter.BloomIFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Bloom 过滤器统一入口。
 *
 * <p><b>本类职责</b>:filter 代理 + fail-open 策略。
 * <ul>
 *   <li>{@link #mightContain}:代理给 {@link BloomIFilter};底层异常时 fail-open(true),
 *       避免误拒绝(可能存在的 key 被短路)</li>
 *   <li>{@link #add}:代理给 {@link BloomIFilter};底层异常仅记录日志不抛出
 *       (不污染缓存写入路径)</li>
 *   <li>{@link #clear}:代理给 {@link BloomIFilter};底层异常仅记录日志不抛出</li>
 * </ul>
 *
 * <p><b>Bloom 语义</b>:过滤器表示「数据源可能存在」的近似集合,而非缓存当前内容的
 * 成员索引。普通缓存 CLEAN 只清缓存数据,<b>不</b>清 Bloom —— 见
 * {@link BloomFilterHandler}:保留旧位只产生安全的 false-positive(请求越过 bloom
 * 走 Redis + loader),清空则产生不安全的 false-negative(loader 被短路、静默返回
 * 业务 null)。本类因此不再承担 rebuilding 窗口 / marker 逻辑;任何底层异常都向
 * fail-open 收敛,保证 loader 可执行。
 *
 * <p><b>线程安全</b>:底层 {@link BloomIFilter} 实现需自行保证并发安全;本类无状态。
 */
@Slf4j
@Component
class BloomSupport {

    private final BloomIFilter bloomIFilter;

    @Autowired
    public BloomSupport(BloomIFilter bloomIFilter) {
        this.bloomIFilter = bloomIFilter;
    }

    /**
     * 判断缓存是否可能存在指定键 —— 单一 fail-open 判定 seam。
     *
     * <p>代理给底层 {@link BloomIFilter#mightContain};底层异常 → true(避免误拒绝)。
     *
     * @param cacheName 缓存名
     * @param key       键(已由 {@link io.github.davidhlp.spring.cache.redis.cache.CacheKeys#bloomKey()}
     *                  派生为 actualKey 形态,与 PUT 时 add 同源)
     * @return 是否可能存在
     */
    public boolean mightContain(final String cacheName, final String key) {
        try {
            return bloomIFilter.mightContain(cacheName, key);
        } catch (Exception ex) {
            log.error("Bloom filter mightContain failed, defaulting to may-contain: cacheName={}",
                    cacheName, ex);
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
            log.error("Bloom filter add failed: cacheName={}", cacheName, ex);
        }
    }

    /**
     * 清空指定缓存对应的 Bloom 过滤器。
     *
     * <p>仅显式管理数据源存在集合时使用;普通缓存 CLEAN 不经过本方法(见
     * {@link BloomFilterHandler})。底层异常仅记录日志,不抛出。
     *
     * @param cacheName 缓存名
     */
    public void clear(final String cacheName) {
        try {
            bloomIFilter.clear(cacheName);
        } catch (Exception ex) {
            log.error("Bloom filter clear failed: cacheName={}", cacheName, ex);
        }
    }
}
