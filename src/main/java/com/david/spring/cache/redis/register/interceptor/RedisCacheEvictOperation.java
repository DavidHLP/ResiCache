package com.david.spring.cache.redis.register.interceptor;

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.cache.interceptor.CacheOperation;

@Data
@Builder
@EqualsAndHashCode(callSuper = true)
public class RedisCacheEvictOperation extends CacheOperation {
	private final boolean sync;
}
