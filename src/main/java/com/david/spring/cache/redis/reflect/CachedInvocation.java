package com.david.spring.cache.redis.reflect;

import com.david.spring.cache.redis.reflect.abstracts.AbstractInvocation;
import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import lombok.*;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.KeyGenerator;

import java.lang.reflect.Method;

/** 缓存调用封装类，用于包装缓存相关的方法调用信息 */
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

	// 懒加载解析的Bean（不参与序列化）
	private KeyGenerator resolvedKeyGenerator;
	private CacheResolver resolvedCacheResolver;

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

}
