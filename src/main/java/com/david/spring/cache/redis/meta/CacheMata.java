package com.david.spring.cache.redis.meta;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CacheMata {
	private long ttl;
	private Object value;
	private Class<?> type;
	private Long visitTimes;
}
