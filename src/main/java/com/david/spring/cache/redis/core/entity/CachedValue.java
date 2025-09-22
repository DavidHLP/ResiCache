package com.david.spring.cache.redis.core.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CachedValue {
	private long ttl;
	private Object value;
	private Class<?> type;
	private Long visitTimes;
}
