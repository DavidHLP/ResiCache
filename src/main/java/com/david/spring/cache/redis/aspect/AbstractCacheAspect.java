package com.david.spring.cache.redis.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;

/**
 * 缓存切面抽象基类
 * 提供基础的切面处理逻辑
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public abstract class AbstractCacheAspect {

	/**
	 * 提取目标方法
	 */
	protected Method extractTargetMethod(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
		if (joinPoint == null) {
			throw new IllegalArgumentException("JoinPoint不能为空");
		}

		Object target = joinPoint.getTarget();
		if (target == null) {
			throw new IllegalArgumentException("目标对象不能为空");
		}

		String methodName = joinPoint.getSignature().getName();
		if (isBlankString(methodName)) {
			throw new IllegalArgumentException("方法名不能为空");
		}

		Class<?>[] parameterTypes = ((MethodSignature) joinPoint.getSignature())
				.getMethod()
				.getParameterTypes();

		return target.getClass().getMethod(methodName, parameterTypes);
	}

	/**
	 * 检查字符串是否为空
	 */
	protected boolean isBlankString(String value) {
		return value == null || value.trim().isEmpty();
	}

	/**
	 * 安全字符串处理
	 */
	protected String safeString(String value) {
		return value == null ? "" : value;
	}
}