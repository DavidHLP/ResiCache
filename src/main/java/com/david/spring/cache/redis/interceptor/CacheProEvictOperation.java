package com.david.spring.cache.redis.interceptor;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.cache.interceptor.CacheEvictOperation;

@Data
@Builder
@EqualsAndHashCode(callSuper = true)
public class CacheProEvictOperation extends CacheEvictOperation {
	private boolean sync;

	public CacheProEvictOperation(Builder builder) {
		super(builder);
	}
}
