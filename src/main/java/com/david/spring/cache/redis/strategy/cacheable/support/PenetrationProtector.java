package com.david.spring.cache.redis.strategy.cacheable.support;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.strategy.cacheable.context.CacheGetContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 缓存穿透保护器
 *
 * @author David
 */
@Slf4j
@Component
public class PenetrationProtector {

	/**
	 * 检查是否允许继续缓存操作
	 *
	 * @param context 缓存获取上下文
	 * @return 如果允许，返回 true；如果被穿透保护拦截，返回 false
	 */
	public boolean isAllowed(CacheGetContext<Object> context) {
		CachedInvocationContext cic = context.getCachedInvocationContext();
		if (cic != null && cic.useBloomFilter() && context.getCachePenetration() != null) {
			String keyStr = String.valueOf(context.getKey());
			if (!context.getCachePenetration().mightContain(context.getCacheName(), keyStr)) {
				log.debug("Cache penetration protection triggered for key: {}", context.getKey());
				return false; // Not allowed
			}
		}
		return true; // Allowed
	}
}
