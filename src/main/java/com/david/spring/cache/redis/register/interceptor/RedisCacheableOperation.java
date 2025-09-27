package com.david.spring.cache.redis.register.interceptor;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.cache.interceptor.CacheOperation;

@Data
@Builder
@EqualsAndHashCode(callSuper = true)
public class RedisCacheableOperation extends CacheOperation {
	private final String unless;

	private final boolean sync;

	private final long ttl;

	private final Class<?> type;

	private final boolean useSecondLevelCache;

	private final boolean distributedLock;

	private final boolean internalLock;

	private final boolean cacheNullValues;

	private final boolean useBloomFilter;

	private final boolean randomTtl;

	private final float variance;

	private final boolean enablePreRefresh;

	private final double preRefreshThreshold;
}

