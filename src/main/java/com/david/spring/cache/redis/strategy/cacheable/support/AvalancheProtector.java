package com.david.spring.cache.redis.strategy.cacheable.support;

import com.david.spring.cache.redis.strategy.cacheable.context.CacheableContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 缓存雪崩保护器
 *
 * @author David
 */
@Slf4j
@Component
public class AvalancheProtector {

	/**
	 * 缓存命中时的处理
	 *
	 * @param context 缓存获取上下文
	 */
	public void onCacheHit(CacheableContext<Object> context) {
		if (context.getCacheAvalanche() != null) {
			log.debug("Cache avalanche protection: cache hit for key: {}", context.getKey());
			// CacheAvalanche 主要用于TTL抖动，访问时一般不需要特殊处理
		}
	}

	/**
	 * 缓存写入时的处理
	 *
	 * @param context 缓存获取上下文
	 */
	public void onCachePut(CacheableContext<Object> context) {
		if (context.getCacheAvalanche() != null) {
			log.debug("Cache avalanche protection: cache write for key: {}", context.getKey());
			// CacheAvalanche 的TTL抖动在缓存配置层面处理，这里主要记录
		}
	}
}
