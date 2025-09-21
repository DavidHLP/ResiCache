package com.david.spring.cache.redis.reflect;

import com.david.spring.cache.redis.reflect.context.EvictInvocationContext;
import com.david.spring.cache.redis.support.BeanResolver;
import com.david.spring.cache.redis.reflect.support.InvocationUtils;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.KeyGenerator;

import java.lang.reflect.Method;

/**
 * 驱逐调用封装类
 * 封装缓存驱逐方法调用的上下文信息
 * 仅用于记录与注册，实际驱逐由 Spring Cache @CacheEvict 机制执行
 */
@Slf4j
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
public class EvictInvocation {

	/** 目标Bean实例 */
	private Object targetBean;

	/** 目标方法 */
	private Method targetMethod;

	/** 方法参数数组 */
	private Object[] arguments;

	/** 驱逐调用上下文信息 */
	private EvictInvocationContext evictInvocationContext;

	/** 懒加载解析的Bean（不参与序列化） */
	@Builder.Default
	private transient KeyGenerator resolvedKeyGenerator = null;

	@Builder.Default
	private transient CacheResolver resolvedCacheResolver = null;

	/**
	 * 执行方法调用
	 */
	public Object invoke() throws Exception {
		return InvocationUtils.invokeMethod(targetBean, targetMethod, arguments, "Evict");
	}

	/**
	 * 验证调用信息的有效性
	 */
	public boolean isValid() {
		return InvocationUtils.isValidInvocation(targetBean, targetMethod, evictInvocationContext);
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
		if (evictInvocationContext == null) {
			return resolvedKeyGenerator;
		}

		resolvedKeyGenerator = BeanResolver.resolveKeyGenerator(
			evictInvocationContext.keyGenerator(), resolvedKeyGenerator);
		return resolvedKeyGenerator;
	}

	/**
	 * 获取CacheResolver实例，优先使用缓存的实例，否则从Context中解析
	 */
	public CacheResolver getCacheResolver() {
		if (evictInvocationContext == null) {
			return resolvedCacheResolver;
		}

		resolvedCacheResolver = BeanResolver.resolveCacheResolver(
			evictInvocationContext.cacheResolver(), resolvedCacheResolver);
		return resolvedCacheResolver;
	}
}
