package com.david.spring.cache.redis.aspect.support;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;

/**
 * Aspect切面工具类
 * 提供切面编程中的通用工具方法
 */
public final class AspectUtils {

	private AspectUtils() {
		// 工具类禁止实例化
	}

	/**
	 * 获取切点的具体方法对象
	 *
	 * @param joinPoint 切入点
	 * @return 具体的方法对象
	 * @throws NoSuchMethodException    如果方法不存在
	 * @throws IllegalArgumentException 如果参数无效
	 */
	public static Method extractTargetMethod(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
		if (joinPoint == null) {
			throw new IllegalArgumentException("JoinPoint cannot be null");
		}

		Object target = joinPoint.getTarget();
		if (target == null) {
			throw new IllegalArgumentException("Target object cannot be null");
		}

		String methodName = joinPoint.getSignature().getName();
		if (isBlankString(methodName)) {
			throw new IllegalArgumentException("Method name cannot be blank");
		}

		Class<?>[] parameterTypes = ((MethodSignature) joinPoint.getSignature())
				.getMethod()
				.getParameterTypes();

		return target.getClass().getMethod(methodName, parameterTypes);
	}

	/**
	 * 安全地将字符串转换为非null值
	 *
	 * @param value 输入字符串
	 * @return 非null的字符串，null转换为空字符串
	 */
	public static String safeString(String value) {
		return value == null ? "" : value;
	}

	/**
	 * 检查字符串是否为空或空白
	 *
	 * @param value 待检查的字符串
	 * @return 如果字符串为null、空字符串或只包含空白字符则返回true
	 */
	public static boolean isBlankString(String value) {
		return value == null || value.trim().isEmpty();
	}
}