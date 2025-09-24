package com.david.spring.cache.redis.interceptor;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.springframework.cache.interceptor.CacheableOperation;

@Getter
public class CacheProAbleOperation extends CacheableOperation {

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

	public CacheProAbleOperation(CacheProAbleOperation.Builder builder) {
		super(builder);
		this.ttl = builder.ttl;
		this.type = builder.type;
		this.useSecondLevelCache = builder.useSecondLevelCache;
		this.distributedLock = builder.distributedLock;
		this.internalLock = builder.internalLock;
		this.cacheNullValues = builder.cacheNullValues;
		this.useBloomFilter = builder.useBloomFilter;
		this.randomTtl = builder.randomTtl;
		this.variance = builder.variance;
		this.enablePreRefresh = builder.enablePreRefresh;
		this.preRefreshThreshold = builder.preRefreshThreshold;
	}

	@Setter
	public static class Builder extends CacheableOperation.Builder {

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

		@Override
		@NonNull
		protected StringBuilder getOperationDescription() {
			StringBuilder sb = super.getOperationDescription();
			sb.append(" | ttl='");
			sb.append(this.ttl);
			sb.append('\'');
			sb.append(" | type='");
			sb.append(this.type != null ? this.type.getSimpleName() : "null");
			sb.append('\'');
			sb.append(" | useSecondLevelCache='");
			sb.append(this.useSecondLevelCache);
			sb.append('\'');
			sb.append(" | distributedLock='");
			sb.append(this.distributedLock);
			sb.append('\'');
			sb.append(" | internalLock='");
			sb.append(this.internalLock);
			sb.append('\'');
			sb.append(" | cacheNullValues='");
			sb.append(this.cacheNullValues);
			sb.append('\'');
			sb.append(" | useBloomFilter='");
			sb.append(this.useBloomFilter);
			sb.append('\'');
			sb.append(" | randomTtl='");
			sb.append(this.randomTtl);
			sb.append('\'');
			sb.append(" | variance='");
			sb.append(this.variance);
			sb.append('\'');
			sb.append(" | enablePreRefresh='");
			sb.append(this.enablePreRefresh);
			sb.append('\'');
			sb.append(" | preRefreshThreshold='");
			sb.append(this.preRefreshThreshold);
			sb.append('\'');
			return sb;
		}

		@Override
		@NonNull
		public CacheProAbleOperation build() {
			return new CacheProAbleOperation(this);
		}
	}
}
