package com.david.spring.cache.redis.register.interceptor;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.lang.NonNull;

@Getter
@EqualsAndHashCode(callSuper = true)
public class RedisCacheEvictOperation extends CacheOperation {
	private final boolean sync;
	private final boolean allEntries;
	private final boolean beforeInvocation;

	protected RedisCacheEvictOperation(Builder b) {
		super(b);
		this.sync = b.sync;
		this.allEntries = b.allEntries;
		this.beforeInvocation = b.beforeInvocation;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder extends CacheOperation.Builder {
		private boolean sync;
		private boolean allEntries;
		private boolean beforeInvocation;

		public Builder name(String name) {
			super.setName(name);
			return this;
		}

		public Builder cacheNames(String... cacheNames) {
			super.setCacheNames(cacheNames);
			return this;
		}

		public Builder key(String key) {
			super.setKey(key);
			return this;
		}

		public Builder keyGenerator(String keyGenerator) {
			super.setKeyGenerator(keyGenerator);
			return this;
		}

		public Builder cacheManager(String cacheManager) {
			super.setCacheManager(cacheManager);
			return this;
		}

		public Builder condition(String condition) {
			super.setCondition(condition);
			return this;
		}

		public Builder cacheResolver(String cacheResolver) {
			super.setCacheResolver(cacheResolver);
			return this;
		}

		public Builder sync(boolean sync) {
			this.sync = sync;
			return this;
		}

		public Builder allEntries(boolean allEntries) {
			this.allEntries = allEntries;
			return this;
		}

		public Builder beforeInvocation(boolean beforeInvocation) {
			this.beforeInvocation = beforeInvocation;
			return this;
		}

		@Override
		@NonNull
		public RedisCacheEvictOperation build() {
			return new RedisCacheEvictOperation(this);
		}
	}
}