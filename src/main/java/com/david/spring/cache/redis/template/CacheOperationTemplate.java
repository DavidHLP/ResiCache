package com.david.spring.cache.redis.template;

import com.david.spring.cache.redis.resolver.CacheOperationResolver;
import org.aspectj.lang.ProceedingJoinPoint;

import java.lang.reflect.Method;

/**
 * 缓存操作模板抽象类
 * 使用模板方法模式定义缓存操作的标准流程
 */
public abstract class CacheOperationTemplate {

	/**
	 * 执行缓存操作的模板方法
	 */
	public final Object execute(ProceedingJoinPoint joinPoint,
	                            CacheOperationResolver.CacheableOperation operation,
	                            Method method,
	                            Object[] args,
	                            Class<?> targetClass) throws Throwable {

		String cacheName = operation.getCacheNames()[0];
		Object cacheKey = generateCacheKey(operation, method, args, joinPoint.getTarget(), targetClass);

		// 检查条件
		if (!shouldExecute(operation, method, args, joinPoint.getTarget(), targetClass)) {
			return joinPoint.proceed();
		}

		// 查询缓存
		Object cachedValue = getCachedValue(operation, cacheKey, cacheName);

		// 如果不是缓存未命中标记，说明缓存命中（包括null值）
		if (!isCacheMissMarker(cachedValue)) {
			return cachedValue;
		}

		// 执行目标方法
		Object result = executeTargetMethod(joinPoint, operation, cacheKey, cacheName);

		return result;
	}

	/**
	 * 生成缓存键
	 */
	protected abstract Object generateCacheKey(CacheOperationResolver.CacheableOperation operation,
	                                           Method method, Object[] args, Object target, Class<?> targetClass);

	/**
	 * 判断是否应该执行缓存操作
	 */
	protected abstract boolean shouldExecute(CacheOperationResolver.CacheableOperation operation,
	                                         Method method, Object[] args, Object target, Class<?> targetClass);

	/**
	 * 获取缓存值
	 */
	protected abstract Object getCachedValue(CacheOperationResolver.CacheableOperation operation,
	                                         Object cacheKey, String cacheName);

	/**
	 * 执行目标方法
	 */
	protected abstract Object executeTargetMethod(ProceedingJoinPoint joinPoint,
	                                              CacheOperationResolver.CacheableOperation operation,
	                                              Object cacheKey, String cacheName) throws Throwable;

	/**
	 * 判断是否为缓存未命中标记
	 */
	protected abstract boolean isCacheMissMarker(Object value);

}