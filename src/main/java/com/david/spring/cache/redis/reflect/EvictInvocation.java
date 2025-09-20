package com.david.spring.cache.redis.reflect;

import com.david.spring.cache.redis.reflect.context.EvictInvocationContext;
import lombok.*;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.util.MethodInvoker;

import java.lang.reflect.Method;

/**
 * 驱逐调用封装类
 * 封装缓存驱逐方法调用的上下文信息
 * 仅用于记录与注册，实际驱逐由 Spring Cache @CacheEvict 机制执行
 */
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
		MethodInvoker invoker = new MethodInvoker();
		invoker.setTargetObject(targetBean);
		invoker.setArguments(arguments);
		invoker.setTargetMethod(targetMethod.getName());
		invoker.prepare();
		return invoker.invoke();
	}
}
