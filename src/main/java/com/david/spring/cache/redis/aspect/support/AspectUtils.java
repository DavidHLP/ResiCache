package com.david.spring.cache.redis.aspect.support;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Aspect切面工具类
 * 提供切面编程中的通用工具方法，包括缓存名称处理
 */
@Slf4j
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
	 * 检查字符串是否为空或空白
	 *
	 * @param value 待检查的字符串
	 * @return 如果字符串为null、空字符串或只包含空白字符则返回true
	 */
	public static boolean isBlankString(String value) {
		return value == null || value.trim().isEmpty();
	}

	/**
	 * 验证并处理缓存名称数组，对有效的缓存名称执行操作
	 *
	 * @param cacheNames 缓存名称数组
	 * @param key        缓存键
	 * @param method     目标方法
	 * @param action     对每个有效缓存名称执行的操作
	 */
	public static void processValidCacheNames(String[] cacheNames, Object key, Method method,
	                                          Consumer<String> action) {
		if (cacheNames == null || cacheNames.length == 0) {
			log.warn("Empty cache names array for method {}", method.getName());
			return;
		}

		List<String> validNames = new ArrayList<>();
		for (String cacheName : cacheNames) {
			if (isValidCacheName(cacheName)) {
				String trimmedName = cacheName.trim();
				validNames.add(trimmedName);
				action.accept(trimmedName);
			}
		}

		if (validNames.isEmpty()) {
			log.warn("No valid cache names found for method {}", method.getName());
		} else {
			log.debug("Processed {} cache names for method {}: {}",
					validNames.size(), method.getName(), validNames);
		}
	}

	/**
	 * 验证缓存名称是否有效
	 *
	 * @param cacheName 缓存名称
	 * @return 如果缓存名称有效则返回true
	 */
	public static boolean isValidCacheName(String cacheName) {
		return !isBlankString(cacheName);
	}
}