package com.david.spring.cache.redis.protection;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于 Redisson 的布隆过滤器辅助类，用于防护缓存穿透。
 *
 * <p>
 * 使用方式： 1) 应用启动或定时任务中，通过 {@link #enableForCache(String, long, double)} 启用并初始化指定
 * cacheName
 * 的布隆过滤器， 并预热（把“合法的业务 Key”批量 add 进入 Bloom）。 2) 在
 * {@code RedisProCache#get(Object,
 * java.util.concurrent.Callable)} 中会在 Bloom 启用后进行判定： - 若 Bloom 断言“不可能存在”，直接返回
 * null，避免回源查询，阻断穿透； -
 * 若成功加载到数据，会自动将对应 key 加入 Bloom。
 *
 * <p>
 * 注意： - 只有显式启用后的 cache 才会应用 Bloom 判定，避免未预热导致的“误伤”正常请求。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CachePenetration {

    private static final String DEFAULT_BLOOM_PREFIX = "bf:cache:";

    private final RedissonClient redissonClient;

    /** 本地缓存：cacheName -> RBloomFilter 句柄（与 Redis 对象同名、同生命周期） */
    private final ConcurrentMap<String, RBloomFilter<String>> filters = new ConcurrentHashMap<>();

    /** 被启用 Bloom 判定的 cache 集合（只有启用后才会生效） */
    private final Set<String> enabledCaches = ConcurrentHashMap.newKeySet();

    /** 计算 Bloom 对象名称（Redis Key） */
    public String bloomName(String cacheName) {
        Objects.requireNonNull(cacheName, "cacheName");
        String name = DEFAULT_BLOOM_PREFIX + cacheName;
        log.debug("Bloom name computed: cacheName={}, bloomKey={}", cacheName, name);
        return name;
    }

    /**
     * 启用并初始化（tryInit）某个缓存对应的 Bloom。通常在启动时或预热任务中调用一次。
     *
     * @param cacheName          缓存名称（与 Spring Cache 的 cacheName 对应）
     * @param expectedInsertions 预估写入数量
     * @param falsePositiveRate  期望误判率（如 0.01 表示 1%）
     */
    public synchronized void enableForCache(
            String cacheName, long expectedInsertions, double falsePositiveRate) {
        Objects.requireNonNull(cacheName, "cacheName");
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException("expectedInsertions must be > 0");
        }
        if (falsePositiveRate <= 0 || falsePositiveRate >= 1) {
            throw new IllegalArgumentException("falsePositiveRate must be in (0,1)");
        }

        String name = bloomName(cacheName);
        RBloomFilter<String> bloom = redissonClient.getBloomFilter(name);
        try {
            log.info(
                    "Enabling Bloom for cache: cacheName={}, bloomKey={}, expectedInsertions={}, fpp={}",
                    cacheName,
                    name,
                    expectedInsertions,
                    falsePositiveRate);
            boolean inited = bloom.tryInit(expectedInsertions, falsePositiveRate);
            if (!inited) {
                // 已存在（可能是其他节点已初始化），忽略即可
                log.info(
                        "Bloom already exists, use existing. cacheName={}, bloomKey={}",
                        cacheName,
                        name);
            } else {
                log.info(
                        "Bloom initialized. cacheName={}, bloomKey={}, expectedInsertions={}, fpp={}",
                        cacheName,
                        name,
                        expectedInsertions,
                        falsePositiveRate);
            }
        } catch (Exception e) {
            log.warn(
                    "Bloom tryInit error, cacheName={}, bloomKey={}, err={}",
                    cacheName,
                    name,
                    e.getMessage());
        }
        filters.put(cacheName, bloom);
        enabledCaches.add(cacheName);
        log.debug("Bloom enabled and cached locally: cacheName={}, bloomKey={}", cacheName, name);
    }

    /** 判断某个 cache 是否启用了 Bloom 判定 */
    public boolean isEnabled(String cacheName) {
        boolean enabled = enabledCaches.contains(cacheName);
        log.debug("Bloom enabled check: cacheName={}, enabled={}", cacheName, enabled);
        return enabled;
    }

    private RBloomFilter<String> filter(String cacheName) {
        return filters.computeIfAbsent(
                cacheName, cn -> redissonClient.getBloomFilter(bloomName(cn)));
    }

    /**
     * Bloom 判定：当 cache 未启用 Bloom 时，返回 true（放行）；启用后返回 contains 结果。 返回 true
     * 表示“可能存在或不确定”，返回 false
     * 表示“几乎不可能存在（直接拦截）”。
     */
    public boolean mightContain(String cacheName, String key) {
        if (!isEnabled(cacheName)) {
            log.debug("Bloom not enabled, allow pass-through: cacheName={}, key={}", cacheName, key);
            return true; // 未启用则不拦截
        }
        try {
            boolean result = filter(cacheName).contains(key);
            log.debug("Bloom contains check: cacheName={}, key={}, mightContain={}", cacheName, key, result);
            return result;
        } catch (Exception e) {
            log.warn(
                    "Bloom contains error, ignore and allow. cacheName={}, key={}, err={}",
                    cacheName,
                    key,
                    e.getMessage());
            return true;
        }
    }

    /** 若已启用 Bloom，则将 key 加入过滤器（幂等） */
    public void addIfEnabled(String cacheName, String key) {
        if (!isEnabled(cacheName)) {
            log.debug("Bloom not enabled, skip add: cacheName={}, key={}", cacheName, key);
            return;
        }
        try {
            boolean added = filter(cacheName).add(key);
            log.debug("Bloom add: cacheName={}, key={}, added={}", cacheName, key, added);
        } catch (Exception e) {
            log.warn(
                    "Bloom add error ignored. cacheName={}, key={}, err={}",
                    cacheName,
                    key,
                    e.getMessage());
        }
    }
}
