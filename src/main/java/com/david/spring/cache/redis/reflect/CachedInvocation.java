package com.david.spring.cache.redis.reflect;

import com.david.spring.cache.redis.reflect.context.CachedInvocationContext;
import lombok.*;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.util.MethodInvoker;

import java.lang.reflect.Method;

/**
 * 缓存调用封装类
 * 包装缓存相关的方法调用信息
 */
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
		MethodInvoker invoker = new MethodInvoker();
		invoker.setTargetObject(targetBean);
		invoker.setArguments(arguments);
		invoker.setTargetMethod(targetMethod.getName());
		invoker.prepare();
		return invoker.invoke();
	}
}
