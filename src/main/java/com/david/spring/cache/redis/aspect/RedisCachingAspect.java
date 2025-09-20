package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.annotation.RedisCaching;
import com.david.spring.cache.redis.aspect.abstracts.AspectAbstract;
import com.david.spring.cache.redis.aspect.constants.AspectConstants;
import com.david.spring.cache.redis.aspect.support.RegisterUtil;
import com.david.spring.cache.redis.registry.impl.CacheInvocationRegistry;
import com.david.spring.cache.redis.registry.impl.EvictInvocationRegistry;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/** Redis缓存切面类，用于处理@RedisCaching注解 该切面优先级高于单个注解的切面，确保复合注解能正确处理 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + AspectConstants.Order.CACHING_ASPECT_ORDER) // 优先级高于单个注解切面
public class RedisCachingAspect extends AspectAbstract {

	// 缓存调用注册中心，用于注册缓存操作
	private final CacheInvocationRegistry cacheRegistry;

	// 缓存驱逐调用注册中心，用于注册缓存驱逐操作
	private final EvictInvocationRegistry evictRegistry;

	/**
	 * 构造函数，注入所需的注册中心
	 *
	 * @param cacheRegistry 缓存调用注册中心
	 * @param evictRegistry 缓存驱逐调用注册中心
	 */
	public RedisCachingAspect(
			CacheInvocationRegistry cacheRegistry, EvictInvocationRegistry evictRegistry) {
		this.cacheRegistry = cacheRegistry;
		this.evictRegistry = evictRegistry;
	}

	/**
	 * 环绕通知，处理@RedisCaching注解
	 *
	 * @param joinPoint    连接点
	 * @param redisCaching RedisCaching注解实例
	 * @return 方法执行结果
	 */
	@SneakyThrows
	@Around("@annotation(redisCaching)")
	public Object around(ProceedingJoinPoint joinPoint, RedisCaching redisCaching) {
		Method method = getSpecificMethod(joinPoint);
		log.debug(AspectConstants.LogMessages.PROCESSING_METHOD,
				"@RedisCaching", method.getDeclaringClass().getSimpleName(), method.getName());
		try {
			// 注册缓存操作
			registerCachingInvocations(joinPoint, redisCaching);
			logProcessingSuccess(method);
		} catch (Exception e) {
			log.error(AspectConstants.LogMessages.REGISTRATION_FAILED,
					getAspectType(), method.getDeclaringClass().getSimpleName(), method.getName(), e.getMessage(), e);
			// 处理注册异常
			handleRegistrationException(e);
		}
		// 继续执行原方法
		return joinPoint.proceed();
	}

	/**
	 * 注册调用信息（重写父类方法） 由于本切面使用带注解参数的环绕通知，此方法不会被直接调用
	 *
	 * @param joinPoint 连接点
	 */
	@Override
	public void registerInvocation(ProceedingJoinPoint joinPoint) {
		// 该方法由带注解参数的环绕通知调用，不在此处直接使用
		throw new UnsupportedOperationException(AspectConstants.ErrorMessages.UNSUPPORTED_OPERATION);
	}

	@Override
	protected String getAspectType() {
		return "RedisCachingAspect";
	}

	/**
	 * 处理RedisCaching复合注解中的所有缓存操作
	 *
	 * @param joinPoint    连接点
	 * @param redisCaching RedisCaching注解实例
	 * @throws NoSuchMethodException 方法未找到异常
	 */
	private void registerCachingInvocations(
			ProceedingJoinPoint joinPoint, RedisCaching redisCaching) throws NoSuchMethodException {

		// 获取目标方法
		Method method = getSpecificMethod(joinPoint);
		// 获取目标对象
		Object targetBean = joinPoint.getTarget();
		// 获取方法参数
		Object[] arguments = joinPoint.getArgs();

		log.debug("Registering cacheable invocations for method: {}", method.getName());
		// 处理缓存注解数组
		for (RedisCacheable cacheable : redisCaching.cacheable()) {
			registerCacheableInvocation(method, targetBean, arguments, cacheable);
		}

		log.debug("Registering evict invocations for method: {}", method.getName());
		// 处理缓存驱逐注解数组
		for (RedisCacheEvict evict : redisCaching.evict()) {
			registerEvictInvocation(method, targetBean, arguments, evict);
		}
	}

	/**
	 * 注册缓存操作调用信息
	 *
	 * @param method         目标方法
	 * @param targetBean     目标对象
	 * @param arguments      方法参数
	 * @param redisCacheable RedisCacheable注解实例
	 */
	private void registerCacheableInvocation(
			Method method,
			Object targetBean,
			Object[] arguments,
			RedisCacheable redisCacheable) {
		// 解析缓存键
		Object key = resolveCacheKey(targetBean, method, arguments, redisCacheable.keyGenerator());
		logResolvedCacheKey("@RedisCacheable", key);
		// 使用工具类注册缓存调用
		RegisterUtil.registerCachingInvocations(
				cacheRegistry, method, targetBean, arguments, redisCacheable, key);
	}

	/**
	 * 注册缓存驱逐操作调用信息
	 *
	 * @param method          目标方法
	 * @param targetBean      目标对象
	 * @param arguments       方法参数
	 * @param redisCacheEvict RedisCacheEvict注解实例
	 */
	private void registerEvictInvocation(
			Method method,
			Object targetBean,
			Object[] arguments,
			RedisCacheEvict redisCacheEvict) {

		// 判断是否清除所有条目
		boolean allEntries = redisCacheEvict.allEntries();
		Object key = null;

		// 如果不是清除所有条目，则需要解析缓存键
		if (!allEntries) {
			key = resolveCacheKey(targetBean, method, arguments, redisCacheEvict.keyGenerator());
			logResolvedCacheKey("@RedisCacheEvict", key);
		} else {
			log.debug("Registering evict invocation for all entries");
		}
		// 使用工具类注册缓存驱逐调用
		RegisterUtil.registerEvictInvocation(
				evictRegistry, method, targetBean, arguments, redisCacheEvict, key);
	}
}
