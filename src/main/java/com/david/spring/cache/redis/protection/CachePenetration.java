package com.david.spring.cache.redis.protection;

import jakarta.annotation.Nonnull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 缓存穿透防护：基于布隆过滤器的穿透保护。
 * <p>
 * 通过布隆过滤器快速判断key是否可能存在，避免对不存在数据的无效查询。
 * 支持动态启用/禁用，批量操作等功能。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class CachePenetration {

	/** 布隆过滤器键名前缀 */
	private static final String BLOOM_PREFIX = "bf:cache:";

	private final RedissonClient redissonClient;

	/** 本地缓存：cacheName -> RBloomFilter 句柄 */
	private final ConcurrentMap<String, RBloomFilter<String>> filters = new ConcurrentHashMap<>();

	/** 已启用布隆过滤器的缓存集合 */
	private final Set<String> enabledCaches = ConcurrentHashMap.newKeySet();

	/**
	 * 判断键是否可能存在
	 *
	 * @param cacheName 缓存名称
	 * @param key       键名
	 * @return true表示可能存在，false表示一定不存在
	 */
	public boolean mightContain(@Nonnull String cacheName, @Nonnull String key) {
		if (!isEnabled(cacheName)) {
			return true; // 未启用则不拦截
		}

		try {
			boolean result = getFilter(cacheName).contains(key);
			log.debug("Bloom filter check: cache={}, key={}, mightContain={}", cacheName, key, result);
			return result;
		} catch (Exception e) {
			log.warn("Bloom filter check failed, allowing request: cache={}, key={}, error={}",
					cacheName, key, e.getMessage());
			return true; // 出错时放行
		}
	}

	/**
	 * 添加键到布隆过滤器
	 *
	 * @param cacheName 缓存名称
	 * @param key       键名
	 */
	public void addIfEnabled(@Nonnull String cacheName, @Nonnull String key) {
		if (!isEnabled(cacheName)) {
			return;
		}

		try {
			getFilter(cacheName).add(key);
			log.debug("Added key to bloom filter: cache={}, key={}", cacheName, key);
		} catch (Exception e) {
			log.warn("Failed to add key to bloom filter: cache={}, key={}, error={}",
					cacheName, key, e.getMessage());
		}
	}

	/**
	 * 判断缓存是否启用了布隆过滤器
	 *
	 * @param cacheName 缓存名称
	 * @return true表示已启用
	 */
	public boolean isEnabled(@Nonnull String cacheName) {
		return enabledCaches.contains(cacheName);
	}

	/**
	 * 获取已启用布隆过滤器的缓存名称集合
	 *
	 * @return 缓存名称集合
	 */
	@Nonnull
	public Set<String> getEnabledCaches() {
		return Set.copyOf(enabledCaches);
	}

	/**
	 * 构建布隆过滤器键名
	 */
	private String buildBloomKey(@Nonnull String cacheName) {
		return BLOOM_PREFIX + cacheName;
	}

	/**
	 * 获取布隆过滤器实例
	 */
	private RBloomFilter<String> getFilter(@Nonnull String cacheName) {
		return filters.computeIfAbsent(cacheName,
				cn -> redissonClient.getBloomFilter(buildBloomKey(cn)));
	}

}
