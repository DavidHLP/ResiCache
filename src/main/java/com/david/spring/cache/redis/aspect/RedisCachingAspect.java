package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.annotation.RedisCaching;
import com.david.spring.cache.redis.aspect.support.CacheAspectSupport;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Redis缓存组合切面，处理@RedisCaching注解。
 * <p>
 * 该切面参考Spring原生@Caching注解的处理逻辑，按照以下顺序处理操作：
 * <ol>
 *   <li>执行beforeInvocation=true的清除操作</li>
 *   <li>执行cacheable操作（缓存查找和方法调用）</li>
 *   <li>执行beforeInvocation=false的清除操作</li>
 * </ol>
 * 这样可以确保缓存操作的正确性和一致性。
 * </p>
 *
 * @author CacheGuard
 * @see RedisCaching
 * @see RedisCacheable
 * @see RedisCacheEvict
 * @since 1.0.0
 */
@Slf4j
@Aspect
@Component
public class RedisCachingAspect extends AbstractCacheAspect {

	/** RedisCacheable切面处理器 */
	private final RedisCacheableAspect redisCacheableAspect;

	/** RedisCacheEvict切面处理器 */
	private final RedisCacheEvictAspect redisCacheEvictAspect;

	/** 缓存切面支持类 */
	private final CacheAspectSupport cacheSupport;

	/**
	 * 构造函数。
	 *
	 * @param redisCacheableAspect  RedisCacheable切面处理器
	 * @param redisCacheEvictAspect RedisCacheEvict切面处理器
	 * @param cacheSupport          缓存切面支持类
	 */
	public RedisCachingAspect(RedisCacheableAspect redisCacheableAspect,
	                          RedisCacheEvictAspect redisCacheEvictAspect,
	                          CacheAspectSupport cacheSupport) {
		this.redisCacheableAspect = redisCacheableAspect;
		this.redisCacheEvictAspect = redisCacheEvictAspect;
		this.cacheSupport = cacheSupport;
	}

	/**
	 * 环绕通知，拦截带有@RedisCaching注解的方法。
	 * <p>
	 * 参考Spring原生Caching注解的处理逻辑：
	 * <ol>
	 *   <li>先处理beforeInvocation=true的清除操作</li>
	 *   <li>然后处理所有cacheable操作</li>
	 *   <li>最后处理beforeInvocation=false的清除操作</li>
	 * </ol>
	 * </p>
	 *
	 * @param joinPoint    连接点，包含方法执行上下文
	 * @param redisCaching 缓存组合注解
	 * @return 方法执行结果
	 * @throws Throwable 方法执行过程中的异常
	 */
	@Around("@annotation(redisCaching)")
	public Object around(ProceedingJoinPoint joinPoint, RedisCaching redisCaching) throws Throwable {
		RedisCacheable[] cacheableAnnotations = redisCaching.cacheable();
		RedisCacheEvict[] evictAnnotations = redisCaching.cacheEvict();

		// 1. 执行方法前的清除操作 (beforeInvocation=true)
		executeBeforeInvocationEvicts(joinPoint, evictAnnotations);

		// 2. 执行缓存操作 - 只有第一个cacheable会真正执行方法，其他的只处理缓存存储
		Object result = executeCacheableOperations(joinPoint, cacheableAnnotations);

		// 3. 执行方法后的清除操作 (beforeInvocation=false)
		executeAfterInvocationEvicts(joinPoint, evictAnnotations);

		return result;
	}

	/**
	 * 执行方法调用前的清除操作
	 */
	private void executeBeforeInvocationEvicts(ProceedingJoinPoint joinPoint, RedisCacheEvict[] evictAnnotations) throws Throwable {
		for (RedisCacheEvict evict : evictAnnotations) {
			if (evict.beforeInvocation()) {
				// 对于beforeInvocation=true的清除操作，直接调用evict切面
				// 注意：这里不需要返回值，因为清除操作不影响方法的执行结果
				redisCacheEvictAspect.around(joinPoint, evict);
			}
		}
	}

	/**
	 * 执行缓存操作 - 第一个cacheable执行完整逻辑，后续只做存储
	 */
	private Object executeCacheableOperations(ProceedingJoinPoint joinPoint, RedisCacheable[] cacheableAnnotations) throws Throwable {
		if (cacheableAnnotations.length == 0) {
			// 没有cacheable注解，直接执行方法
			return joinPoint.proceed();
		}

		// 第一个cacheable操作处理完整的缓存逻辑（查找、执行、存储）
		Object result = redisCacheableAspect.around(joinPoint, cacheableAnnotations[0]);

		// 后续的cacheable操作只负责将结果存储到不同的缓存中
		// 这样可以确保每个cacheable的TTL等配置都能正确传递
		if (cacheableAnnotations.length > 1) {
			Method method = extractTargetMethod(joinPoint);
			Object targetBean = joinPoint.getTarget();
			Object[] arguments = joinPoint.getArgs();

			for (int i = 1; i < cacheableAnnotations.length; i++) {
				try {
					// 直接使用storeToCaches方法存储，避免重复的缓存查找逻辑
					cacheSupport.storeToCaches(cacheableAnnotations[i], method, targetBean, arguments, result);
					log.debug("存储结果到额外缓存: annotation={}", cacheableAnnotations[i]);
				} catch (Exception e) {
					log.warn("存储到额外缓存失败: annotation={}, error={}", cacheableAnnotations[i], e.getMessage(), e);
				}
			}
		}

		return result;
	}

	/**
	 * 执行方法调用后的清除操作
	 */
	private void executeAfterInvocationEvicts(ProceedingJoinPoint joinPoint, RedisCacheEvict[] evictAnnotations) throws Throwable {
		for (RedisCacheEvict evict : evictAnnotations) {
			if (!evict.beforeInvocation()) {
				// 对于beforeInvocation=false的清除操作，在方法执行后调用
				redisCacheEvictAspect.around(joinPoint, evict);
			}
		}
	}

}