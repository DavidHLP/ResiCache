package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.aspect.support.AspectUtils;
import com.david.spring.cache.redis.aspect.support.CacheNameUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;

/**
 * 缓存切面抽象基类
 * 提供通用的切面处理逻辑和模板方法
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public abstract class AbstractCacheAspect {

	/**
	 * 通用的环绕通知处理模板
	 *
	 * @param joinPoint     切入点
	 * @param operationType 操作类型描述（cache/evict）
	 * @return 方法执行结果
	 * @throws Throwable 方法执行异常
	 */
	protected Object executeAroundAdvice(ProceedingJoinPoint joinPoint, String operationType) throws Throwable {
		try {
			processInvocation(joinPoint);
		} catch (Exception e) {
			log.warn("Failed to register {} invocation for method {}: {}",
					operationType, joinPoint.getSignature().getName(), e.getMessage(), e);
		}
		return joinPoint.proceed();
	}

	/**
	 * 提取切面基础信息的通用方法
	 *
	 * @param joinPoint 切入点
	 * @return 切面执行上下文
	 * @throws NoSuchMethodException 方法不存在异常
	 */
	protected AspectExecutionContext extractExecutionContext(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
		Method method = AspectUtils.extractTargetMethod(joinPoint);
		Object targetBean = joinPoint.getTarget();
		Object[] arguments = joinPoint.getArgs();

		return new AspectExecutionContext(method, targetBean, arguments);
	}

	/**
	 * 注册调用的通用模板方法
	 *
	 * @param cacheNames 缓存名称数组
	 * @param key        缓存键
	 * @param method     目标方法
	 * @param registrar  具体地注册逻辑
	 */
	protected void registerForCaches(String[] cacheNames, Object key, Method method, CacheRegistrar registrar) {
		CacheNameUtils.processValidCacheNames(cacheNames, key, method, cacheName ->
				registrar.register(cacheName, key)
		);
	}

	/**
	 * 子类需要实现的具体调用处理逻辑
	 *
	 * @param joinPoint 切入点
	 * @throws Exception 处理异常
	 */
	protected abstract void processInvocation(ProceedingJoinPoint joinPoint) throws Exception;

	/**
	 * 缓存注册器函数式接口
	 */
	@FunctionalInterface
	protected interface CacheRegistrar {
		void register(String cacheName, Object key);
	}

	/**
	 * 切面执行上下文封装类
	 */
	public record AspectExecutionContext(
			Method method,
			Object targetBean,
			Object[] arguments
	) {}
}