package com.david.spring.cache.redis.core.writer.chain.handler;

import lombok.Builder;

@Builder
public record LockContext(boolean syncLock, String lockKey, long timeoutSeconds) {

	public boolean requiresLock() {
		return syncLock && lockKey != null && !lockKey.isBlank();
	}
}
