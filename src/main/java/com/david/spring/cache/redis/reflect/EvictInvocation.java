package com.david.spring.cache.redis.reflect;

import com.david.spring.cache.redis.reflect.abstracts.AbstractInvocation;
import com.david.spring.cache.redis.reflect.context.EvictInvocationContext;
import lombok.*;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.KeyGenerator;

import java.lang.reflect.Method;

/** 封装一次缓存驱逐（Evict）方法调用的上下文信息。 仅用于记录与注册，实际驱逐由 Spring Cache @CacheEvict 机制与 Cache 实现执行。 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class EvictInvocation extends AbstractInvocation {

	/** 目标 Bean（被 AOP 拦截的对象） */
	private Object targetBean;

	/** 目标方法 */
	private Method targetMethod;

	/** 调用参数 */
	private Object[] arguments;

	/** 缓存驱逐上下文信息 */
	private EvictInvocationContext evictInvocationContext;

	/** 已解析的 KeyGenerator Bean（延迟加载，不参与序列化） */
	private KeyGenerator resolvedKeyGenerator;

	/** 已解析的 CacheResolver Bean（延迟加载，不参与序列化） */
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
