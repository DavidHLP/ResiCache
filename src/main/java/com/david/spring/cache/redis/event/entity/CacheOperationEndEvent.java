package com.david.spring.cache.redis.event.entity;

import com.david.spring.cache.redis.event.CacheEvent;
import com.david.spring.cache.redis.event.CacheEventType;
import lombok.Getter;

/**
 * 缓存操作结束事件
 */
@Getter
public class CacheOperationEndEvent extends CacheEvent {
	private final String operation;
	private final long totalTime;
	private final String methodName;
	private final boolean successful;

	public CacheOperationEndEvent(String cacheName, Object cacheKey, String source,
	                              String operation, String methodName, long totalTime,
	                              boolean successful) {
		super(cacheName, cacheKey, source);
		this.operation = operation;
		this.methodName = methodName;
		this.totalTime = totalTime;
		this.successful = successful;
	}

	@Override
	public CacheEventType getEventType() {
		return CacheEventType.CACHE_OPERATION_END;
	}
}