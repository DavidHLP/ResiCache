package com.david.spring.cache.redis.template;

import com.david.spring.cache.redis.core.CacheOperationResolver;
import com.david.spring.cache.redis.event.entity.*;
import com.david.spring.cache.redis.event.publisher.CacheEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 缓存操作模板抽象类
 * 使用模板方法模式定义缓存操作的标准流程
 */
@Slf4j
@RequiredArgsConstructor
public abstract class CacheOperationTemplate {

	protected final CacheEventPublisher eventPublisher;

	/**
	 * 执行缓存操作的模板方法
	 */
	public final Object execute(ProceedingJoinPoint joinPoint,
	                            CacheOperationResolver.CacheableOperation operation,
	                            Method method,
	                            Object[] args,
	                            Class<?> targetClass) throws Throwable {

		long startTime = System.currentTimeMillis();
		String cacheName = operation.getCacheNames()[0];
		Object cacheKey = generateCacheKey(operation, method, args, joinPoint.getTarget(), targetClass);
		String source = targetClass.getSimpleName() + "." + method.getName();
		String methodName = method.getName();

		// 发布操作开始事件
		onCacheOperationStart(operation, cacheKey, cacheName, source, methodName);

		try {
			// 前置处理
			beforeCacheOperation(operation, cacheKey, cacheName);

			// 检查条件
			if (!shouldExecute(operation, method, args, joinPoint.getTarget(), targetClass, null)) {
				log.debug("Condition not met, skipping cache operation");
				return joinPoint.proceed();
			}

			// 查询缓存
			Object cachedValue = getCachedValue(operation, cacheKey, cacheName);

			// 如果不是缓存未命中标记，说明缓存命中（包括null值）
			if (!isCacheMissMarker(cachedValue)) {
				// 缓存命中处理
				onCacheHit(operation, cacheKey, cacheName, cachedValue, source, startTime);
				return cachedValue;
			}

			// 缓存未命中处理
			onCacheMiss(operation, cacheKey, cacheName, source, "cache_not_found");

			// 执行目标方法
			Object result = executeTargetMethod(joinPoint, operation, cacheKey, cacheName);

			// 后置处理
			afterCacheOperation(operation, cacheKey, cacheName, result, startTime);

			// 发布操作结束事件（成功）
			onCacheOperationEnd(operation, cacheKey, cacheName, source, methodName,
					System.currentTimeMillis() - startTime, true);

			return result;

		} catch (Exception e) {
			// 异常处理
			onCacheError(operation, cacheKey, cacheName, e, "execute");

			// 发布操作结束事件（失败）
			onCacheOperationEnd(operation, cacheKey, cacheName, source, methodName,
					System.currentTimeMillis() - startTime, false);
			throw e;
		}
	}

	/**
	 * 生成缓存键 - 子类需要实现
	 */
	protected abstract Object generateCacheKey(CacheOperationResolver.CacheableOperation operation,
	                                           Method method, Object[] args, Object target, Class<?> targetClass);

	/**
	 * 判断是否应该执行缓存操作 - 子类需要实现
	 */
	protected abstract boolean shouldExecute(CacheOperationResolver.CacheableOperation operation,
	                                         Method method, Object[] args, Object target, Class<?> targetClass, Object result);

	/**
	 * 获取缓存值 - 子类需要实现
	 */
	protected abstract Object getCachedValue(CacheOperationResolver.CacheableOperation operation,
	                                         Object cacheKey, String cacheName);

	/**
	 * 执行目标方法 - 子类需要实现
	 */
	protected abstract Object executeTargetMethod(ProceedingJoinPoint joinPoint,
	                                              CacheOperationResolver.CacheableOperation operation,
	                                              Object cacheKey, String cacheName) throws Throwable;

	/**
	 * 缓存结果 - 子类需要实现
	 */
	protected abstract void cacheResult(CacheOperationResolver.CacheableOperation operation,
	                                    Object cacheKey, String cacheName, Object result, Duration ttl);

	/**
	 * 前置处理 - 子类可以重写
	 */
	protected void beforeCacheOperation(CacheOperationResolver.CacheableOperation operation,
	                                    Object cacheKey, String cacheName) {
		log.debug("Starting cache operation for key: {} in cache: {}", cacheKey, cacheName);
	}

	/**
	 * 后置处理 - 子类可以重写
	 */
	protected void afterCacheOperation(CacheOperationResolver.CacheableOperation operation,
	                                   Object cacheKey, String cacheName, Object result, long startTime) {
		log.debug("Completed cache operation for key: {} in cache: {}", cacheKey, cacheName);
	}

	/**
	 * 缓存命中处理 - 子类可以重写
	 */
	protected void onCacheHit(CacheOperationResolver.CacheableOperation operation,
	                          Object cacheKey, String cacheName, Object value,
	                          String source, long startTime) {
		long accessTime = System.currentTimeMillis() - startTime;
		log.debug("Cache hit for key: {} in cache: {}, access time: {}ms", cacheKey, cacheName, accessTime);

		if (eventPublisher != null) {
			CacheHitEvent event = new CacheHitEvent(cacheName, cacheKey, source, value, accessTime);
			eventPublisher.publishEventAsync(event);
		}
	}

	/**
	 * 缓存未命中处理 - 子类可以重写
	 */
	protected void onCacheMiss(CacheOperationResolver.CacheableOperation operation,
	                           Object cacheKey, String cacheName, String source, String reason) {
		log.debug("Cache miss for key: {} in cache: {}, reason: {}", cacheKey, cacheName, reason);

		if (eventPublisher != null) {
			CacheMissEvent event = new CacheMissEvent(cacheName, cacheKey, source, reason);
			eventPublisher.publishEventAsync(event);
		}
	}

	/**
	 * 缓存结果处理 - 子类可以重写
	 */
	protected void onCachePut(CacheOperationResolver.CacheableOperation operation,
	                          Object cacheKey, String cacheName, Object value,
	                          String source, Duration ttl, long executionTime) {
		log.debug("Cache put for key: {} in cache: {}, TTL: {}, execution time: {}ms",
				cacheKey, cacheName, ttl, executionTime);

		if (eventPublisher != null) {
			CachePutEvent event = new CachePutEvent(cacheName, cacheKey, source, value, ttl, executionTime);
			eventPublisher.publishEventAsync(event);
		}
	}

	/**
	 * 缓存错误处理 - 子类可以重写
	 */
	protected void onCacheError(CacheOperationResolver.CacheableOperation operation,
	                            Object cacheKey, String cacheName, Exception e, String operationType) {
		log.warn("Cache operation error for key: {} in cache: {}: {}", cacheKey, cacheName, e.getMessage());

		if (eventPublisher != null) {
			CacheErrorEvent event = new CacheErrorEvent(cacheName, cacheKey,
					operation.getClass().getSimpleName(), e, operationType);
			eventPublisher.publishEventAsync(event);
		}
	}

	/**
	 * 缓存操作开始处理 - 子类可以重写
	 */
	protected void onCacheOperationStart(CacheOperationResolver.CacheableOperation operation,
	                                     Object cacheKey, String cacheName, String source, String methodName) {
		log.debug("Cache operation starting for key: {} in cache: {}, method: {}", cacheKey, cacheName, methodName);

		if (eventPublisher != null) {
			CacheOperationStartEvent event = new CacheOperationStartEvent(cacheName, cacheKey, source,
					"cacheable", methodName);
			eventPublisher.publishEventAsync(event);
		}
	}

	/**
	 * 缓存操作结束处理 - 子类可以重写
	 */
	protected void onCacheOperationEnd(CacheOperationResolver.CacheableOperation operation,
	                                   Object cacheKey, String cacheName, String source, String methodName,
	                                   long totalTime, boolean successful) {
		log.debug("Cache operation completed for key: {} in cache: {}, method: {}, time: {}ms, success: {}",
				cacheKey, cacheName, methodName, totalTime, successful);

		if (eventPublisher != null) {
			CacheOperationEndEvent event = new CacheOperationEndEvent(cacheName, cacheKey, source,
					"cacheable", methodName, totalTime, successful);
			eventPublisher.publishEventAsync(event);
		}
	}

	/**
	 * 判断是否为缓存未命中标记 - 子类需要实现
	 */
	protected boolean isCacheMissMarker(Object value) {
		return false; // 默认实现，子类可以重写
	}
}