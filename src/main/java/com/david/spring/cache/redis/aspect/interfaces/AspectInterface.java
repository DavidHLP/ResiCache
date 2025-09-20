package com.david.spring.cache.redis.aspect.interfaces;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;

/**
 * Redis 缓存切面接口，定义通用的切面操作
 */
public interface AspectInterface {

	/**
	 * 切面环绕通知的核心方法
	 *
	 * @param joinPoint 切入点
	 * @return 方法执行结果
	 * @throws Throwable 方法执行异常
	 */
	Object around(ProceedingJoinPoint joinPoint) throws Throwable;

	/**
	 * 注册方法调用信息
	 *
	 * @param joinPoint 切入点
	 * @throws NoSuchMethodException 方法不存在异常
	 */
	void registerInvocation(ProceedingJoinPoint joinPoint) throws NoSuchMethodException;

	/**
	 * 获取具体的方法对象
	 *
	 * @param joinPoint 切入点
	 * @return 方法对象
	 * @throws NoSuchMethodException 方法不存在异常
	 */
	default Method getSpecificMethod(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
		Object target = joinPoint.getTarget();
		String methodName = joinPoint.getSignature().getName();
		Class<?>[] parameterTypes = ((MethodSignature) joinPoint.getSignature()).getMethod().getParameterTypes();
		return target.getClass().getMethod(methodName, parameterTypes);
	}
}
