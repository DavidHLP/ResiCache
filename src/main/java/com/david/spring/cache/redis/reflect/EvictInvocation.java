package com.david.spring.cache.redis.reflect;

import com.david.spring.cache.redis.reflect.abstracts.AbstractInvocation;
import com.david.spring.cache.redis.reflect.context.EvictInvocationContext;
import com.david.spring.cache.redis.reflect.interfaces.InvocationContext;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

import java.lang.reflect.Method;

/**
 * 封装一次缓存驱逐（Evict）方法调用的上下文信息。
 * 仅用于记录与注册，实际驱逐由 Spring Cache @CacheEvict 机制与 Cache 实现执行。
 * 主人，这个类现在也变得更加简洁了喵~
 */
@Slf4j
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
		return evictInvocationContext;
	}
}
