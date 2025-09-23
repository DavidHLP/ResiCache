package com.david.spring.cache.redis.cache.support;

import com.david.spring.cache.redis.chain.CacheHandlerChainBuilder;
import com.david.spring.cache.redis.chain.CacheHandlerContext;
import com.david.spring.cache.redis.reflect.CachedInvocation;
import org.springframework.cache.Cache;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 缓存处理器执行器。
 * <p>
 * 负责创建和执行缓存处理器链，替代原来的CacheStrategyExecutor。
 * </p>
 *
 * @author CacheGuard
 * @since 2.0.0
 */
@Component
public class CacheHandlerExecutor {

	private final CacheHandlerChainBuilder chainBuilder;
	private final HandlerChainExecutor handlerChainExecutor;

	public CacheHandlerExecutor(CacheHandlerChainBuilder chainBuilder) {
		this.chainBuilder = chainBuilder;
		this.handlerChainExecutor = new HandlerChainExecutor(chainBuilder);
	}

	/**
	 * 创建缓存获取回调。
	 *
	 * @param cacheName        缓存名称
	 * @param cache            缓存实例
	 * @param operationService 缓存操作服务
	 * @return 缓存获取回调
	 */
	public CacheHandlerContext.CacheFetchCallback createFetchCallback(String cacheName,
	                                                                  Cache cache,
	                                                                  CacheOperationService operationService) {
		return CacheFetchCallbackFactory.create(cacheName, cache, operationService);
	}

	/**
	 * 创建处理器上下文。
	 *
	 * @param cacheName     缓存名称
	 * @param key           缓存键
	 * @param cacheKey      Redis缓存键
	 * @param valueWrapper  缓存值包装器
	 * @param invocation    缓存调用信息
	 * @param redisTemplate Redis模板
	 * @param callback      回调接口
	 * @return 处理器上下文
	 */
	public CacheHandlerContext createHandlerContext(String cacheName,
	                                                Object key,
	                                                String cacheKey,
	                                                Cache.ValueWrapper valueWrapper,
	                                                CachedInvocation invocation,
	                                                RedisTemplate<String, Object> redisTemplate,
	                                                CacheHandlerContext.CacheFetchCallback callback) {
		return createHandlerContext(cacheName, key, cacheKey, valueWrapper,
				invocation, redisTemplate, callback, com.david.spring.cache.redis.chain.CacheOperationType.READ);
	}

	/**
	 * 创建处理器上下文（带操作类型）。
	 *
	 * @param cacheName     缓存名称
	 * @param key           缓存键
	 * @param cacheKey      Redis缓存键
	 * @param valueWrapper  缓存值包装器
	 * @param invocation    缓存调用信息
	 * @param redisTemplate Redis模板
	 * @param callback      回调接口
	 * @param operationType 操作类型
	 * @return 处理器上下文
	 */
	public CacheHandlerContext createHandlerContext(String cacheName,
	                                                Object key,
	                                                String cacheKey,
	                                                Cache.ValueWrapper valueWrapper,
	                                                CachedInvocation invocation,
	                                                RedisTemplate<String, Object> redisTemplate,
	                                                CacheHandlerContext.CacheFetchCallback callback,
	                                                com.david.spring.cache.redis.chain.CacheOperationType operationType) {
		return new CacheHandlerContext(
				cacheName,
				key,
				cacheKey,
				valueWrapper,
				null, // 初始时没有处理结果
				invocation,
				invocation.getCachedInvocationContext(),
				redisTemplate,
				callback,
				operationType
		);
	}

	/**
	 * 执行处理器链并处理异常。
	 *
	 * @param context       处理器上下文
	 * @param fallbackValue 回退值
	 * @param key           缓存键
	 * @param cacheName     缓存名称
	 * @return 处理结果
	 */
	public Cache.ValueWrapper executeHandlersWithFallback(CacheHandlerContext context,
	                                                      Cache.ValueWrapper fallbackValue,
	                                                      Object key,
	                                                      String cacheName) {
		return handlerChainExecutor.execute(context, fallbackValue);
	}
}