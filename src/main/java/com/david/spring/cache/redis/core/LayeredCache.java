package com.david.spring.cache.redis.core;

import com.david.spring.cache.redis.event.publisher.CacheEventPublisher;
import com.david.spring.cache.redis.template.CacheOperationTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.lang.Nullable;

import java.util.concurrent.Callable;

import static com.david.spring.cache.redis.core.CacheConstants.*;

/**
 * 分层缓存实现
 * 结合本地缓存和Redis缓存，提供两级缓存机制
 */
@Slf4j
public class LayeredCache implements Cache {

	private final String name;
	private final Cache localCache;
	private final Cache remoteCache;
	// 事件支持（通过父类）
	private final AbstractEventAwareCache eventSupport = new AbstractEventAwareCache() {};
	// 模板支持
	private CacheOperationTemplate operationTemplate;
	// 智能同步配置
	private boolean intelligentSyncEnabled = false;
	private long syncThresholdMs = DEFAULT_SYNC_THRESHOLD_MS;

	public LayeredCache(String name, Cache localCache, Cache remoteCache) {
		this.name = name;
		this.localCache = localCache;
		this.remoteCache = remoteCache;
	}

	/**
	 * 设置事件发布器
	 */
	public void setEventPublisher(CacheEventPublisher eventPublisher) {
		eventSupport.setEventPublisher(eventPublisher);
	}

	/**
	 * 设置操作模板
	 */
	public void setOperationTemplate(CacheOperationTemplate operationTemplate) {
		this.operationTemplate = operationTemplate;
	}

	/**
	 * 启用智能同步
	 */
	public void enableIntelligentSync(long syncThresholdMs) {
		this.intelligentSyncEnabled = true;
		this.syncThresholdMs = syncThresholdMs;
		log.info("Intelligent sync enabled for cache '{}' with threshold {}ms", name, syncThresholdMs);
	}

	/**
	 * 禁用智能同步
	 */
	public void disableIntelligentSync() {
		this.intelligentSyncEnabled = false;
		log.info("Intelligent sync disabled for cache '{}'", name);
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Object getNativeCache() {
		return this;
	}

	@Override
	@Nullable
	public ValueWrapper get(Object key) {
		long startTime = System.currentTimeMillis();

		// 发布操作开始事件
		eventSupport.publishOperationStartEvent(name, key, CacheLayers.LAYERED_CACHE, Operations.GET, Operations.GET);

		try {
			// 先查本地缓存
			ValueWrapper localValue = localCache.get(key);
			if (localValue != null) {
				long accessTime = System.currentTimeMillis() - startTime;

				eventSupport.logCacheOperation(Operations.GET, key, "local-hit", accessTime);

				// 发布本地命中事件
				eventSupport.publishCacheHitEvent(name, key, CacheLayers.LAYERED_CACHE + "-" + CacheLayers.LOCAL, localValue.get(), accessTime);
				eventSupport.publishOperationEndEvent(name, key, CacheLayers.LAYERED_CACHE, Operations.GET, Operations.GET, accessTime, true);
				return localValue;
			}

			// 查远程缓存
			ValueWrapper remoteValue = remoteCache.get(key);
			if (remoteValue != null) {
				long accessTime = System.currentTimeMillis() - startTime;

				// 智能同步决策
				if (shouldSyncToLocal(accessTime)) {
					localCache.put(key, remoteValue.get());
				}

				eventSupport.logCacheOperation(Operations.GET, key, "remote-hit", accessTime);
				// 发布远程命中事件
				eventSupport.publishCacheHitEvent(name, key, CacheLayers.LAYERED_CACHE + "-" + CacheLayers.REMOTE, remoteValue.get(), accessTime);
				eventSupport.publishOperationEndEvent(name, key, CacheLayers.LAYERED_CACHE, Operations.GET, Operations.GET, accessTime, true);
				return remoteValue;
			}

			// 缓存未命中
			long missTime = System.currentTimeMillis() - startTime;

			eventSupport.logCacheOperation(Operations.GET, key, "miss", missTime);

			// 发布未命中事件
			eventSupport.publishCacheMissEvent(name, key, CacheLayers.LAYERED_CACHE, MissReasons.NOT_FOUND_IN_BOTH_LAYERS);
			eventSupport.publishOperationEndEvent(name, key, CacheLayers.LAYERED_CACHE, Operations.GET, Operations.GET, missTime, true);
			return null;

		} catch (Exception e) {
			long errorTime = System.currentTimeMillis() - startTime;

			eventSupport.logCacheError(Operations.GET, key, e.getMessage());

			// 发布错误事件
			eventSupport.publishCacheErrorEvent(name, key, CacheLayers.LAYERED_CACHE, e, Operations.GET);
			eventSupport.publishOperationEndEvent(name, key, CacheLayers.LAYERED_CACHE, Operations.GET, Operations.GET, errorTime, false);
			throw e;
		}
	}

	@Override
	@Nullable
	public <T> T get(Object key, Class<T> type) {
		ValueWrapper wrapper = get(key);
		return wrapper != null ? (T) wrapper.get() : null;
	}

	@Override
	@Nullable
	public <T> T get(Object key, Callable<T> valueLoader) {
		ValueWrapper result = get(key);
		if (result != null) {
			return (T) result.get();
		}

		try {
			T value = valueLoader.call();
			put(key, value);
			return value;
		} catch (Exception e) {
			throw new ValueRetrievalException(key, valueLoader, e);
		}
	}

	@Override
	public void put(Object key, @Nullable Object value) {
		// 同时更新本地和远程缓存
		localCache.put(key, value);
		remoteCache.put(key, value);
		eventSupport.logCacheOperation(Operations.PUT, key, "success-both-layers");
	}

	@Override
	public ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
		// 先检查本地缓存
		ValueWrapper existing = localCache.get(key);
		if (existing != null) {
			return existing;
		}

		// 检查远程缓存并尝试设置
		ValueWrapper remoteResult = remoteCache.putIfAbsent(key, value);
		if (remoteResult == null) {
			// 远程缓存中不存在，更新本地缓存
			localCache.put(key, value);
			eventSupport.logCacheOperation("putIfAbsent", key, "success");
			return null;
		} else {
			// 远程缓存中已存在，更新本地缓存
			localCache.put(key, remoteResult.get());
			eventSupport.logCacheOperation("putIfAbsent", key, "exists");
			return remoteResult;
		}
	}

	@Override
	public void evict(Object key) {
		// 从两级缓存中删除
		localCache.evict(key);
		remoteCache.evict(key);
		eventSupport.logCacheOperation(Operations.EVICT, key, "success-both-layers");
	}

	@Override
	public void clear() {
		// 清空两级缓存
		localCache.clear();
		remoteCache.clear();
		eventSupport.logCacheOperation(Operations.CLEAR, "*", "success-both-layers");
	}

	/**
	 * 获取本地缓存
	 */
	public Cache getLocalCache() {
		return localCache;
	}

	/**
	 * 获取远程缓存
	 */
	public Cache getRemoteCache() {
		return remoteCache;
	}

	/**
	 * 智能同步决策
	 */
	private boolean shouldSyncToLocal(long accessTime) {
		if (!intelligentSyncEnabled) {
			return true; // 默认总是同步
		}

		// 基于访问时间的智能决策
		// 如果远程访问时间超过阈值，说明网络延迟较高，应该同步到本地
		return accessTime > syncThresholdMs;
	}
}