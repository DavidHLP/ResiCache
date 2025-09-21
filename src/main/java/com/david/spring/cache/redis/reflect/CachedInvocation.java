package com.david.spring.cache.redis.reflect;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import com.david.spring.cache.redis.reflect.support.InvocationUtils;
import com.david.spring.cache.redis.support.BeanResolver;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.KeyGenerator;

import java.lang.reflect.Method;

/**
 * 缓存调用封装类
 * 包装缓存相关的方法调用信息
 */
@Slf4j
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
public class CachedInvocation {

	/** 目标Bean实例 */
	private Object targetBean;

	/** 目标方法 */
	private Method targetMethod;
	/** 方法参数数组 */
	private Object[] arguments;

	/** 缓存调用上下文信息 */
	private CachedInvocationContext cachedInvocationContext;

	/** 懒加载解析的Bean（不参与序列化） */
	@Builder.Default
	private transient KeyGenerator resolvedKeyGenerator = null;

	@Builder.Default
	private transient CacheResolver resolvedCacheResolver = null;

	/**
	 * 执行方法调用
	 */
	public Object invoke() throws Exception {
		return InvocationUtils.invokeMethod(targetBean, targetMethod, arguments, "Cache");
	}

	/**
	 * 验证调用信息的有效性
	 */
	public boolean isValid() {
		return InvocationUtils.isValidInvocation(targetBean, targetMethod, cachedInvocationContext);
	}

	/**
	 * 获取方法签名的字符串表示
	 */
	public String getMethodSignature() {
		return InvocationUtils.getMethodSignature(targetBean, targetMethod);
	}

	/**
	 * 获取KeyGenerator实例，优先使用缓存的实例，否则从Context中解析
	 */
	public KeyGenerator getKeyGenerator() {
		if (cachedInvocationContext == null) {
			return resolvedKeyGenerator;
		}

		resolvedKeyGenerator = BeanResolver.resolveKeyGenerator(
				cachedInvocationContext.keyGenerator(), resolvedKeyGenerator);
		return resolvedKeyGenerator;
	}

	/**
	 * 获取CacheResolver实例，优先使用缓存的实例，否则从Context中解析
	 */
	public CacheResolver getCacheResolver() {
		if (cachedInvocationContext == null) {
			return resolvedCacheResolver;
		}

		resolvedCacheResolver = BeanResolver.resolveCacheResolver(
				cachedInvocationContext.cacheResolver(), resolvedCacheResolver);
		return resolvedCacheResolver;
	}
}
