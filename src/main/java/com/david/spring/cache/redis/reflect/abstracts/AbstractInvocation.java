package com.david.spring.cache.redis.reflect.abstracts;

import com.david.spring.cache.redis.reflect.interfaces.InvocationContext;
import com.david.spring.cache.redis.reflect.interfaces.InvocationInterface;
import com.david.spring.cache.redis.reflect.support.BeanResolvableSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.lang.Nullable;
import org.springframework.util.MethodInvoker;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 抽象缓存调用基类，提供通用的方法调用和Bean解析功能
 * 主人，这个基类现在更加通用和强大了喵~
 */
@Slf4j
public abstract class AbstractInvocation implements InvocationInterface {

	/** Bean 解析支持，延迟初始化 */
	private BeanResolvableSupport beanSupport;

	protected abstract Object getTargetBean();

	protected abstract Method getTargetMethod();

	protected abstract Object[] getArguments();

	/**
	 * 获取调用上下文信息
	 *
	 * @return 调用上下文
	 */
	@Nullable
	protected abstract InvocationContext getInvocationContext();

	@Override
	public Object invoke() throws Exception {
		final Object target = this.getTargetBean();
		final Method method = this.getTargetMethod();
		final Object[] args = this.getArguments();

		final String targetClass = (target != null) ? target.getClass().getName() : "null";
		final String methodName = (method != null) ? method.getName() : "null";
		final int argCount = (args != null) ? args.length : 0;
		final String contextType = getContextType();

		log.info("[{}] Invoking method: {}.{} with {} argument(s)", contextType, targetClass, methodName, argCount);
		if (log.isDebugEnabled()) {
			log.debug("[{}] Invocation details -> target: {}, method: {}, args: {}", contextType, target, method, args);
		}

		try {
			final MethodInvoker invoker = new MethodInvoker();
			invoker.setTargetObject(target);
			invoker.setArguments(Objects.requireNonNull(args));
			invoker.setTargetMethod(methodName);
			invoker.prepare();

			final Object result = invoker.invoke();
			if (result == null) {
				log.info("[{}] Invocation completed: {}.{} returned null", contextType, targetClass, methodName);
			} else {
				log.info("[{}] Invocation completed: {}.{} returned type {}", contextType, targetClass, methodName,
						result.getClass().getName());
				if (log.isDebugEnabled()) {
					log.debug("[{}] Invocation result toString(): {}", contextType, result);
				}
			}
			return result;
		} catch (Exception e) {
			log.error("[{}] Invocation failed for {}.{} with {} argument(s)", contextType, targetClass, methodName, argCount, e);
			throw e;
		}
	}

	/**
	 * 获取上下文类型，用于日志标识
	 *
	 * @return 上下文类型
	 */
	protected String getContextType() {
		InvocationContext context = getInvocationContext();
		return context != null ? context.getContextType() : getClass().getSimpleName();
	}

	/**
	 * 懒加载解析 KeyGenerator Bean
	 *
	 * @return KeyGenerator 实例，如果解析失败返回 null
	 */
	@Nullable
	public KeyGenerator resolveKeyGenerator() {
		return getBeanSupport().resolveKeyGenerator();
	}

	/**
	 * 懒加载解析 CacheResolver Bean
	 *
	 * @return CacheResolver 实例，如果解析失败返回 null
	 */
	@Nullable
	public CacheResolver resolveCacheResolver() {
		return getBeanSupport().resolveCacheResolver();
	}

	/** 清除已缓存的解析 Bean，强制下次调用时重新从上下文解析 */
	public void clearResolved() {
		if (beanSupport != null) {
			beanSupport.clearResolved();
		}
	}

	/**
	 * 获取 Bean 解析状态信息
	 */
	public BeanResolvableSupport.BeanResolveStatus getBeanResolveStatus() {
		return getBeanSupport().getResolveStatus();
	}

	/**
	 * 获取或创建 Bean 解析支持实例
	 *
	 * @return Bean 解析支持实例
	 */
	private BeanResolvableSupport getBeanSupport() {
		if (beanSupport == null) {
			beanSupport = createBeanSupport();
		}
		return beanSupport;
	}

	/**
	 * 创建 Bean 解析支持实例
	 *
	 * @return Bean 解析支持实例
	 */
	private BeanResolvableSupport createBeanSupport() {
		return new BeanResolvableSupport() {
			@Override
			@Nullable
			protected String getKeyGeneratorName() {
				InvocationContext context = getInvocationContext();
				return context != null ? context.getKeyGenerator() : null;
			}

			@Override
			@Nullable
			protected String getCacheResolverName() {
				InvocationContext context = getInvocationContext();
				return context != null ? context.getCacheResolver() : null;
			}

			@Override
			protected String getTargetMethodDescription() {
				Method method = getTargetMethod();
				String contextType = getContextType();
				if (method == null) {
					return contextType + "[unknown]";
				}
				return String.format("%s[%s#%s]", contextType,
						method.getDeclaringClass().getSimpleName(),
						method.getName());
			}
		};
	}
}
