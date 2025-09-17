package com.david.spring.cache.redis.reflect;

import com.david.spring.cache.redis.reflect.abstracts.AbstractInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.reflect.interfaces.InvocationContext;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.lang.reflect.Method;

/**
 * 缓存调用封装类
 * 使用不可变设计，提供线程安全的缓存调用信息
 *
 * @author David
 */
@Slf4j
@Value
@Builder
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class CachedInvocation extends AbstractInvocation {

	/** 目标Bean实例 */
	@NonNull
	Object targetBean;

	/** 目标方法 */
	@NonNull
	Method targetMethod;

	/** 方法参数数组（可以为空但不能为null） */
	@NonNull
	Object[] arguments;

	/** 缓存调用上下文信息 */
	@Nullable
	CachedInvocationContext cachedInvocationContext;

	@Override
	protected Object getTargetBean() {
		return targetBean;
	}

	@Override
	protected Method getTargetMethod() {
		return targetMethod;
	}

	@Override
	protected Object[] getArguments() {
		return arguments;
	}

	@Override
	@Nullable
	protected InvocationContext getInvocationContext() {
		return cachedInvocationContext;
	}

	/**
	 * 检查调用是否有有效的上下文
	 *
	 * @return 如果有有效上下文则返回true
	 */
	public boolean hasValidContext() {
		return cachedInvocationContext != null && cachedInvocationContext.isValidTtlConfig();
	}

	/**
	 * 获取方法签名字符串（用于日志和调试）
	 *
	 * @return 方法签名字符串
	 */
	public String getMethodSignature() {
		return targetMethod.getDeclaringClass().getSimpleName() +
			   "#" + targetMethod.getName() +
			   "(" + arguments.length + " args)";
	}

	/**
	 * 检查是否配置了缓存保护
	 *
	 * @return 如果配置了任何保护机制则返回true
	 */
	public boolean hasProtectionConfig() {
		return cachedInvocationContext != null && cachedInvocationContext.needsProtection();
	}

	/**
	 * 构建器类，提供验证和默认值
	 */
	public static class CachedInvocationBuilder {

		/**
		 * 构建并验证调用实例
		 *
		 * @return 验证后的调用实例
		 * @throws IllegalArgumentException 如果必要参数缺失或无效
		 */
		public CachedInvocation build() {
			if (targetBean == null) {
				throw new IllegalArgumentException("Target bean cannot be null");
			}
			if (targetMethod == null) {
				throw new IllegalArgumentException("Target method cannot be null");
			}
			if (arguments == null) {
				arguments = new Object[0]; // 默认空数组
			}

			// 验证上下文的有效性
			if (cachedInvocationContext != null && !cachedInvocationContext.isValidTtlConfig()) {
				throw new IllegalArgumentException("Invalid TTL configuration in context");
			}

			return new CachedInvocation(targetBean, targetMethod, arguments, cachedInvocationContext);
		}
	}
}
