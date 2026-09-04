package io.github.davidhlp.spring.cache.redis.protection.bloom;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.davidhlp.spring.cache.redis.config.RedisProCacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Bloom 过滤器 rebuilding 窗口状态机 —— 从 {@link BloomSupport} 抽出的 deep seam。
 *
 * <p>本类收口 rebuilding 窗口的读/写/失效/禁用判定,让 {@link BloomSupport}
 * 回归纯代理 + fail-open 角色。{@link BloomFilterHandler} 的外部 API 不变(mightContain /
 * add / clear)。
 *
 * <p><b>deletion test</b>:删本类 + 内联回 {@link BloomSupport} → 60+ 行状态机 + 本地 Caffeine
 * 缓存 + Redis 标志协议在 BloomSupport 中段重新出现,关注点交织。seam 挣得起存在代价。
 *
 * <p><b>Spring 装配</b>:{@code @Component} + {@code @Autowired} 构造注入。{@code properties}
 * 为 null(测试场景)时 {@code rebuildWindowSeconds=0} → 禁用语义。
 *
 * <p><b>线程安全</b>:本地 Caffeine cache 单写多读(失效操作并发安全);Redis 标志由
 * 跨实例共享(per-cacheName key 唯一),多 JVM 写入冲突由 SETNX 不必要 —— 后写覆盖即
 * 表示最新窗口边界,行为正确。
 */
@Slf4j
@Component
public class BloomRebuilder {

    /** rebuilding 标志的 Redis key 前缀(独立协调标志,不走缓存 keyPrefix) */
    static final String REBUILD_KEY_PREFIX = "resicache:bloom:rebuild:";

    /** rebuilding 状态本地缓存 TTL(秒):容忍此延迟的跨实例不一致 */
    static final long REBUILD_LOCAL_CACHE_TTL_SECONDS = 1L;

    /** rebuilding 窗口禁用阈值(秒):{@code <=} 此值表示禁用 */
    static final long REBUILD_WINDOW_DISABLED = 0L;

    private final RedisTemplate<String, Object> redisTemplate;
    private final long rebuildWindowSeconds;
    private final Cache<String, Boolean> rebuildingCache;

    /**
     * @param redisTemplate 用于 rebuilding 协调标志的 Redis 模板
     * @param properties    全局配置(读取 rebuild-window-seconds);可为 null(测试)
     */
    @Autowired
    @SuppressWarnings("unchecked")
    public BloomRebuilder(
            @Qualifier("redisCacheTemplate") RedisTemplate<String, ?> redisTemplate,
            @Nullable RedisProCacheProperties properties) {
        this.redisTemplate = (RedisTemplate<String, Object>) (RedisTemplate<?, ?>) redisTemplate;
        this.rebuildWindowSeconds = properties == null
                ? REBUILD_WINDOW_DISABLED
                : properties.getBloomFilter().getRebuildWindowSeconds();
        this.rebuildingCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(REBUILD_LOCAL_CACHE_TTL_SECONDS))
                .build();
    }

    /**
     * 是否处于 rebuilding 窗口 —— {@link BloomSupport#mightContain} 的 fail-open 判定源。
     *
     * <p>三级短路:
     * <ol>
     *   <li>{@code rebuildWindowSeconds <= 0} → 直接返回 false(窗口禁用)</li>
     *   <li>本地 Caffeine 缓存命中 → 返回缓存值(避免每次都打 Redis)</li>
     *   <li>Caffeine miss → 查 Redis rebuilding 标志,缓存并返回</li>
     * </ol>
     *
     * <p>Redis 查询异常时退化到 false(假设未在 rebuilding,不阻断 fail-open 语义)
     * ——{@link BloomSupport} 在本类返回 false 后会照常查底层 bloom。
     *
     * @param cacheName 缓存名(per-cacheName 独立窗口)
     * @return true 表示正在 rebuilding 窗口期,调用方应 fail-open
     */
    public boolean isRebuilding(String cacheName) {
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

    /**
     * 在 Redis 写入 per-cacheName 的 rebuilding 标志(TTL=window),并失效本地缓存使下次
     * {@link #isRebuilding} 立即查到 true。
     *
     * <p>窗口由 Redis TTL 自动到期结束,无需猜测重建 key 数量。
     * 窗口禁用时本方法为 no-op。
     * 标志写入失败仅记日志(退化为无窗口语义,不阻断 {@link BloomSupport#clear} 主流程)。
     *
     * @param cacheName 缓存名
     */
    public void markRebuilding(String cacheName) {
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
            // 标志设置失败不阻断 clear 本身;最坏退化为无 rebuilding 窗口
            log.error("Failed to mark bloom rebuilding window (falling back to legacy no-window behavior): cacheName={}",
                    cacheName, ex);
        }
    }

    /** 构造 rebuilding 标志的 Redis key */
    static String rebuildKey(String cacheName) {
        return REBUILD_KEY_PREFIX + cacheName;
    }

    /**
     * 当前 rebuilding 窗口配置 —— 仅供诊断 / 测试 / 日志读取使用。
     *
     * @return 配置的窗口秒数(0 表示禁用)
     */
    public long getRebuildWindowSeconds() {
        return rebuildWindowSeconds;
    }
}
