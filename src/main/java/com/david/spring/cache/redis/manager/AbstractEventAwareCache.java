package com.david.spring.cache.redis.manager;

import lombok.extern.slf4j.Slf4j;

/**
 * 抽象缓存基类
 * 提供通用的缓存操作日志记录功能
 */
@Slf4j
public abstract class AbstractEventAwareCache {

	public void publishOperationStartEvent(String cacheName, Object key, String source, String operation, String displayName) {
		log.debug("Cache operation start: cache='{}', key='{}', source='{}', operation='{}', displayName='{}'",
				cacheName, key, source, operation, displayName);
	}

	public void publishOperationEndEvent(String cacheName, Object key, String source, String operation,
	                                     String displayName, long operationTime, boolean successful) {
		log.debug("Cache operation end: cache='{}', key='{}', source='{}', operation='{}', displayName='{}', time={}ms, success='{}'",
				cacheName, key, source, operation, displayName, operationTime, successful);
	}

	public void publishCacheErrorEvent(String cacheName, Object key, String source, Exception exception, String operation) {
		log.warn("Cache error: cache='{}', key='{}', source='{}', operation='{}', error='{}'",
				cacheName, key, source, operation, exception.getMessage());
	}

	public void logCacheOperation(String operation, Object key, String result) {
		log.debug("Cache {}: key='{}', result='{}'", operation, key, result);
	}

	public void logCacheError(String operation, Object key, String errorMessage) {
		log.warn("Cache {} failed: key='{}', error='{}'", operation, key, errorMessage);
	}
}