package com.david.spring.cache.redis.interceptor;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.springframework.cache.interceptor.CacheEvictOperation;


@Getter
public class CacheProEvictOperation extends CacheEvictOperation {

	private final boolean sync;

	public CacheProEvictOperation(CacheProEvictOperation.Builder b) {
		super(b);
		this.sync = b.sync;
	}

	@Setter
	public static class Builder extends CacheEvictOperation.Builder {

		private boolean sync = false;

		@Override
		@NonNull
		protected StringBuilder getOperationDescription() {
			StringBuilder sb = super.getOperationDescription();
			sb.append(',');
			sb.append(this.sync);
			return sb;
		}

		@Override
		@NonNull
		public CacheProEvictOperation build() {
			return new CacheProEvictOperation(this);
		}
	}
}
