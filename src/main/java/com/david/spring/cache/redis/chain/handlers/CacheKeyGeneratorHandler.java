package com.david.spring.cache.redis.chain.handlers;

import com.david.spring.cache.redis.chain.AbstractCacheHandler;
import com.david.spring.cache.redis.chain.context.CacheContext;
import com.david.spring.cache.redis.parser.CacheKeyGenerator;
import com.david.spring.cache.redis.parser.CacheNameGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 缓存键生成处理器
 * 基于Spring CacheAspectSupport的键生成逻辑
 *
 * @author david
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheKeyGeneratorHandler extends AbstractCacheHandler {

	private final CacheKeyGenerator cacheKeyGenerator;
	private final CacheNameGenerator cacheNameGenerator;

	@Override
	public String getName() {
		return "CacheKeyGeneratorHandler";
	}

	@Override
	protected Object doHandle(CacheContext context) throws Throwable {
		// 生成缓存键
		String cacheKey = generateCacheKey(context);
		context.setCacheKey(cacheKey);

		// 设置缓存名称
		String cacheName = getCacheName(context);
		context.setCacheName(cacheName);

		log.debug("生成缓存键: {}, 缓存名称: {}", cacheKey, cacheName);

		// 继续下一个处理器
		return null;
	}

	/**
	 * 生成缓存键
	 */
	private String generateCacheKey(CacheContext context) {
		String keyExpression = context.getRedisCacheable().key();
		return cacheKeyGenerator.generateCacheKey(
			keyExpression,
			context.getTarget(),
			context.getMethod(),
			context.getArgs()
		);
	}

	/**
	 * 获取缓存名称
	 */
	private String getCacheName(CacheContext context) {
		return cacheNameGenerator.getCacheName(
			context.getRedisCacheable().cacheNames(),
			context.getRedisCacheable().value()
		);
	}
}
