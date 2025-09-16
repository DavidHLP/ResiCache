package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.aspect.abstracts.AspectAbstract;
import com.david.spring.cache.redis.aspect.support.RegisterUtil;
import com.david.spring.cache.redis.registry.CacheInvocationRegistry;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RedisCacheableAspect extends AspectAbstract {

	private final CacheInvocationRegistry registry;

	public RedisCacheableAspect(CacheInvocationRegistry registry) {
		this.registry = registry;
	}

	@SneakyThrows
	@Around("@annotation(redisCacheable)")
	public Object around(ProceedingJoinPoint joinPoint, RedisCacheable redisCacheable) {
		Method method = getSpecificMethod(joinPoint);
		log.debug("Processing @RedisCacheable annotation for method: {}.{}",
				method.getDeclaringClass().getSimpleName(), method.getName());
		try {
			registerCacheableInvocation(joinPoint, redisCacheable);
		} catch (Exception e) {
			log.error("Failed to register cacheable invocation for method: {}.{} - {}",
					method.getDeclaringClass().getSimpleName(), method.getName(), e.getMessage(), e);
			handleRegistrationException(e);
		}
		return joinPoint.proceed();
	}

	@Override
	public void registerInvocation(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
		// 此方法由带注解参数的 around 方法调用具体实现，这里不直接使用
		throw new UnsupportedOperationException("Please use the around method with annotation parameter");
	}

	/**
	 * 注册缓存调用信息的具体实现
	 */
	private void registerCacheableInvocation(ProceedingJoinPoint joinPoint, RedisCacheable redisCacheable)
			throws NoSuchMethodException {

		Method method = getSpecificMethod(joinPoint);
		Object targetBean = joinPoint.getTarget();
		Object[] arguments = joinPoint.getArgs();

		Object key = resolveCacheKey(targetBean, method, arguments, redisCacheable.keyGenerator());

		RegisterUtil.registerCachingInvocations(registry, joinPoint, method, targetBean, arguments, redisCacheable, key);
	}

}
