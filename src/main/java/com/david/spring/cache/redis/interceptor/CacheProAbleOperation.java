package com.david.spring.cache.redis.interceptor;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.cache.interceptor.CacheableOperation;

@Data
@Builder
@EqualsAndHashCode(callSuper = true)
public class CacheProAbleOperation extends CacheableOperation {

	private long ttl;
	private Class<?> type;
	private boolean useSecondLevelCache;
	private boolean distributedLock;
	private boolean internalLock;
	private boolean cacheNullValues;
	private boolean useBloomFilter;
	private boolean randomTtl;
	private float variance;
	private boolean enablePreRefresh;
	private double preRefreshThreshold;

	public CacheProAbleOperation(Builder builder) {
		super(builder);
	}
}
