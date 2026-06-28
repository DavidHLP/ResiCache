package io.github.davidhlp.spring.cache.redis.protection.bloom;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import io.github.davidhlp.spring.cache.redis.protection.bloom.filter.BloomIFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Bloom 过滤器统一入口,屏蔽底层实现细节并提供降级兜底。
 *
 * <p><b>Rebuilding 窗口(WS-1.2c)</b>:{@link #clear(String)} 清空过滤器后会进入一个
 * per-cacheName 的 <b>rebuilding 期</b>(长度由
 * {@code resi-cache.bloom-filter.rebuild-window-seconds} 控制,默认 30s,0=禁用)。
 * 期间 {@link #mightContain(String, String)} <b>fail-open</b>(一律返回 true),让请求越过
 * bloom 短路、走正常 sync 锁 + loader 路径,避免"CLEAN 后静默返回 null 违反
 * {@code @Cacheable} 契约"的数据正确性缺陷。
 *
 * <p><b>背景</b>:CLEAN({@code @CacheEvict(allEntries=true)})清空布隆后,空布隆对所有 key
 * 判定 {@code mightContain=false},导致后续 GET 在 {@code RedisProCache.get(key, loader)}
 * 的前置短路处<b>静默返回 null</b>(既不查缓存也不调 loader)—— 这违反 Spring
 * {@code @Cacheable}"miss 即调 loader 返回真实值"的契约,是数据正确性缺陷(loader 未被
 * 调用,故非 DB 击穿)。rebuilding 窗口用短暂的 fail-open 把请求导向 loader,由 PUT 回填
 * 重建布隆,窗口由 Redis TTL 自动结束,无需猜测重建 key 数量。
 *
 * <p><b>Cluster 一致性</b>:rebuilding 标志存于 Redis(而非仅 local),保证多实例一致;
 * 另用 Caffeine 短缓存(1s)避免每次 {@code mightContain} 额外 Redis 查询,容忍秒级跨实例
 * 不一致(对秒级 ~30s 的窗口无实质影响)。
 *
 * @see io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties.BloomFilterProperties#getRebuildWindowSeconds()
 */
@Slf4j
@Component
public class BloomSupport {

    /** rebuilding 标志的 Redis key 前缀(独立协调标志,不走缓存 keyPrefix) */
    private static final String REBUILD_KEY_PREFIX = "resicache:bloom:rebuild:";

    /** rebuilding 状态本地缓存 TTL(秒):容忍此延迟的跨实例不一致 */
    private static final long REBUILD_LOCAL_CACHE_TTL_SECONDS = 1L;

    /** rebuilding 窗口禁用阈值(秒):{@code <=} 此值表示禁用,保持 v0.0.x 旧行为 */
    private static final long REBUILD_WINDOW_DISABLED = 0L;

    private final BloomIFilter bloomIFilter;
    private final RedisTemplate<String, String> redisTemplate;
    private final long rebuildWindowSeconds;
    private final Cache<String, Boolean> rebuildingCache;

    /**
     * @param bloomIFilter  底层布隆过滤器(Hierarchical: local + redis)
     * @param redisTemplate 用于 rebuilding 协调标志的 Redis 模板
     * @param properties    全局配置(读取 rebuild-window-seconds)
     */
    @Autowired
    public BloomSupport(final BloomIFilter bloomIFilter,
                        final RedisTemplate<String, String> redisTemplate,
                        final RedisProCacheProperties properties) {
        this.bloomIFilter = bloomIFilter;
        this.redisTemplate = redisTemplate;
        this.rebuildWindowSeconds = properties.getBloomFilter().getRebuildWindowSeconds();
        this.rebuildingCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(REBUILD_LOCAL_CACHE_TTL_SECONDS))
                .build();
    }

    /**
     * 判断缓存是否可能存在指定键。
     *
     * <p>rebuilding 期内 fail-open(返回 true),使请求越过 bloom 短路进入 sync 锁 + loader,
     * 避免 CLEAN 后静默 null。底层异常时同样 fail-open 以避免误拒绝。
     */
    public boolean mightContain(final String cacheName, final String key) {
        if (isRebuilding(cacheName)) {
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
     * 将指定键加入 Bloom 过滤器,异常时仅记录日志。
     */
    public void add(final String cacheName, final String key) {
        try {
            bloomIFilter.add(cacheName, key);
        } catch (Exception ex) {
            log.error("Bloom filter add failed: cacheName={}, key={}", cacheName, key, ex);
        }
    }

    /**
     * 清空指定缓存对应的 Bloom 过滤器,并(若启用)开启 rebuilding 窗口。
     *
     * <p>即使底层 clear 抛异常,仍尝试开启 rebuilding 窗口(标志是 Redis 独立操作),
     * 以最大程度保护 fail-open 语义;标志写入失败也不抛出(退化为无窗口旧行为)。
     */
    public void clear(final String cacheName) {
        try {
            bloomIFilter.clear(cacheName);
        } catch (Exception ex) {
            log.error("Bloom filter clear failed: cacheName={}", cacheName, ex);
        }
        markRebuilding(cacheName);
    }

    /**
     * 在 Redis 写入 per-cacheName 的 rebuilding 标志(TTL=window),并失效本地缓存使下次
     * {@link #isRebuilding} 立即查到 true。窗口由 Redis TTL 自动到期结束。
     */
    private void markRebuilding(final String cacheName) {
        if (rebuildWindowSeconds <= REBUILD_WINDOW_DISABLED) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    rebuildKey(cacheName), "1", Duration.ofSeconds(rebuildWindowSeconds));
            rebuildingCache.invalidate(cacheName);
            log.warn("Bloom filter cleared; rebuilding window opened ({}s, fail-open): cacheName={}",
                    rebuildWindowSeconds, cacheName);
        } catch (Exception ex) {
            // 标志设置失败不阻断 clear 本身;最坏退化为无 rebuilding 窗口(即 v0.0.x 旧行为)
            log.error("Failed to mark bloom rebuilding window (falling back to legacy no-window behavior): cacheName={}",
                    cacheName, ex);
        }
    }

    /**
     * 是否处于 rebuilding 窗口。先查本地 Caffeine 缓存(避每次 Redis 查询),miss 则查 Redis
     * 是否存在 rebuilding 标志,并缓存结果。
     */
    private boolean isRebuilding(final String cacheName) {
        if (rebuildWindowSeconds <= REBUILD_WINDOW_DISABLED) {
            return false;
        }
        final Boolean cached = rebuildingCache.getIfPresent(cacheName);
        if (cached != null) {
            return cached;
        }
        boolean rebuilding;
        try {
            rebuilding = Boolean.TRUE.equals(redisTemplate.hasKey(rebuildKey(cacheName)));
        } catch (Exception ex) {
            log.debug("Bloom rebuild-flag check failed, assume not rebuilding: cacheName={}", cacheName, ex);
            rebuilding = false;
        }
        rebuildingCache.put(cacheName, rebuilding);
        return rebuilding;
    }

    private static String rebuildKey(final String cacheName) {
        return REBUILD_KEY_PREFIX + cacheName;
    }
}
