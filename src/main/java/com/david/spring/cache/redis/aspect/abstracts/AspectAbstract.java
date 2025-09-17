package com.david.spring.cache.redis.aspect.abstracts;

import com.david.spring.cache.redis.aspect.constants.AspectConstants;
import com.david.spring.cache.redis.aspect.interfaces.AspectInterface;
import com.david.spring.cache.redis.aspect.support.KeyResolver;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;

import java.lang.reflect.Method;

/**
 * 抽象切面类，实现通用的切面功能
 * 提供统一的异常处理、日志记录和缓存键解析逻辑
 *
 * @author David
 */
@Slf4j
public abstract class AspectAbstract implements AspectInterface {

	@Override
	public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
		Method method = getSpecificMethod(joinPoint);
		String methodName = getMethodSignature(method);

		log.debug("Processing aspect for method: {}", methodName);

		try {
			registerInvocation(joinPoint);
		} catch (Exception e) {
			log.error(AspectConstants.LogMessages.REGISTRATION_FAILED,
					getAspectType(), method.getDeclaringClass().getSimpleName(), method.getName(), e.getMessage(), e);
			handleRegistrationException(e);
		}

		return joinPoint.proceed();
	}

	/**
	 * 处理注册异常的策略方法，子类可以重写
	 *
	 * @param e 异常信息
	 */
	protected void handleRegistrationException(Exception e) {
		log.warn(AspectConstants.ErrorMessages.REGISTRATION_WARNING, e.getMessage());
	}

	/**
	 * 解析缓存键
	 *
	 * @param targetBean           目标对象
	 * @param method               目标方法
	 * @param arguments            方法参数
	 * @param keyGeneratorBeanName KeyGenerator Bean 名称
	 * @return 缓存键
	 */
	protected Object resolveCacheKey(Object targetBean, Method method, Object[] arguments,
	                                 String keyGeneratorBeanName) {
		return KeyResolver.resolveKey(targetBean, method, arguments, keyGeneratorBeanName);
	}

	/**
	 * 获取缓存名称数组
	 *
	 * @param values     缓存值数组
	 * @param cacheNames 缓存名称数组
	 * @return 合并后的缓存名称数组
	 */
	protected String[] getCacheNames(String[] values, String[] cacheNames) {
		return KeyResolver.getCacheNames(values, cacheNames);
	}

	/**
	 * 获取方法签名字符串，用于日志输出
	 *
	 * @param method 目标方法
	 * @return 方法签名字符串
	 */
	protected String getMethodSignature(Method method) {
		return method.getDeclaringClass().getSimpleName() + "." + method.getName();
	}

	/**
	 * 记录切面处理成功的日志
	 *
	 * @param method 目标方法
	 */
	protected void logProcessingSuccess(Method method) {
		log.debug(AspectConstants.LogMessages.REGISTRATION_SUCCESS,
				getAspectType(), method.getDeclaringClass().getSimpleName(), method.getName());
	}

	/**
	 * 记录缓存键解析结果
	 *
	 * @param annotationType 注解类型
	 * @param key            解析的缓存键
	 */
	protected void logResolvedCacheKey(String annotationType, Object key) {
		log.debug(AspectConstants.LogMessages.RESOLVED_CACHE_KEY, annotationType, key);
	}

	/**
	 * 获取当前切面类型名称，子类需要实现
	 *
	 * @return 切面类型名称
	 */
	protected abstract String getAspectType();
}
