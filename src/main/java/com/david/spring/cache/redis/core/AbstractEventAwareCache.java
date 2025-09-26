package com.david.spring.cache.redis.core;

import com.david.spring.cache.redis.event.entity.*;
import com.david.spring.cache.redis.event.publisher.CacheEventPublisher;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * 支持事件发布的抽象缓存基类
 * 减少各缓存实现中事件发布代码的重复
 */
@Setter
@Slf4j
public abstract class AbstractEventAwareCache {

	protected CacheEventPublisher eventPublisher;
	protected boolean eventDrivenEnabled = true;
	protected boolean detailedEventsEnabled = false;

	/**
	 * 发布缓存命中事件
	 */
	protected void publishCacheHitEvent(String cacheName, Object key, String source, Object value, long accessTime) {
		if (isEventPublishEnabled()) {
			CacheHitEvent event = new CacheHitEvent(cacheName, key, source, value, accessTime);
			eventPublisher.publishEventAsync(event);
		}
	}

	/**
	 * 发布缓存未命中事件
	 */
	protected void publishCacheMissEvent(String cacheName, Object key, String source, String reason) {
		if (isEventPublishEnabled()) {
			CacheMissEvent event = new CacheMissEvent(cacheName, key, source, reason);
			eventPublisher.publishEventAsync(event);
		}
	}

	/**
	 * 发布缓存写入事件
	 */
	protected void publishCachePutEvent(String cacheName, Object key, String source, Object value, Duration ttl, long executionTime) {
		if (isEventPublishEnabled()) {
			CachePutEvent event = new CachePutEvent(cacheName, key, source, value, ttl, executionTime);
			eventPublisher.publishEventAsync(event);
		}
	}

	/**
	 * 发布操作开始事件
	 */
	protected void publishOperationStartEvent(String cacheName, Object key, String source, String operation, String displayName) {
		if (isEventPublishEnabled() && detailedEventsEnabled) {
			CacheOperationStartEvent event = new CacheOperationStartEvent(cacheName, key, source, operation, displayName);
			eventPublisher.publishEventAsync(event);
		}
	}

	/**
	 * 发布操作结束事件
	 */
	protected void publishOperationEndEvent(String cacheName, Object key, String source, String operation,
	                                        String displayName, long operationTime, boolean successful) {
		if (isEventPublishEnabled() && detailedEventsEnabled) {
			CacheOperationEndEvent event = new CacheOperationEndEvent(cacheName, key, source, operation, displayName, operationTime, successful);
			eventPublisher.publishEventAsync(event);
		}
	}

	/**
	 * 发布错误事件
	 */
	protected void publishCacheErrorEvent(String cacheName, Object key, String source, Exception exception, String operation) {
		if (isEventPublishEnabled()) {
			CacheErrorEvent event = new CacheErrorEvent(cacheName, key, source, exception, operation);
			eventPublisher.publishEventAsync(event);
		}
	}

	/**
	 * 检查是否可以发布事件
	 */
	protected boolean isEventPublishEnabled() {
		return eventPublisher != null && eventDrivenEnabled;
	}

	/**
	 * 通用的日志记录方法
	 */
	protected void logCacheOperation(String operation, Object key, String result) {
		log.debug("Cache {}: key='{}', result='{}'", operation, key, result);
	}

	protected void logCacheOperation(String operation, Object key, String result, long timeMs) {
		log.debug("Cache {}: key='{}', result='{}', time={}ms", operation, key, result, timeMs);
	}

	protected void logCacheError(String operation, Object key, String errorMessage) {
		log.warn("Cache {} failed: key='{}', error='{}'", operation, key, errorMessage);
	}
}