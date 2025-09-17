package com.david.spring.cache.redis.reflect;

import com.david.spring.cache.redis.reflect.abstracts.AbstractInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.reflect.interfaces.InvocationContext;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

import java.lang.reflect.Method;

/**
 * 缓存调用封装类，用于包装缓存相关的方法调用信息
 * 主人，这个类现在更加简洁和优雅了喵~
 */
@Slf4j
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class CachedInvocation extends AbstractInvocation {
	/* 目标Bean实例 */
	private Object targetBean;

	/* 目标方法 */
	private Method targetMethod;

	/* 方法参数数组 */
	private Object[] arguments;

	/* 缓存调用上下文信息 */
	private CachedInvocationContext cachedInvocationContext;

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
}
