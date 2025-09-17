package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.aspect.abstracts.AspectAbstract;
import com.david.spring.cache.redis.aspect.constants.AspectConstants;
import com.david.spring.cache.redis.aspect.support.RegisterUtil;
import com.david.spring.cache.redis.registry.impl.CacheInvocationRegistry;
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
@Order(Ordered.HIGHEST_PRECEDENCE + AspectConstants.Order.SINGLE_ASPECT_ORDER)
public class RedisCacheableAspect extends AspectAbstract {

	private final CacheInvocationRegistry registry;

	public RedisCacheableAspect(CacheInvocationRegistry registry) {
		this.registry = registry;
	}

	@SneakyThrows
	@Around("@annotation(redisCacheable)")
	public Object around(ProceedingJoinPoint joinPoint, RedisCacheable redisCacheable) {
		Method method = getSpecificMethod(joinPoint);
		log.debug(AspectConstants.LogMessages.PROCESSING_METHOD,
				"@RedisCacheable", method.getDeclaringClass().getSimpleName(), method.getName());
		try {
			registerCacheableInvocation(joinPoint, redisCacheable);
			logProcessingSuccess(method);
		} catch (Exception e) {
			log.error(AspectConstants.LogMessages.REGISTRATION_FAILED,
					getAspectType(), method.getDeclaringClass().getSimpleName(), method.getName(), e.getMessage(), e);
			handleRegistrationException(e);
		}
		return joinPoint.proceed();
	}

	@Override
	public void registerInvocation(ProceedingJoinPoint joinPoint) {
		// 此方法由带注解参数的 around 方法调用具体实现，这里不直接使用
		throw new UnsupportedOperationException(AspectConstants.ErrorMessages.UNSUPPORTED_OPERATION);
	}

	@Override
	protected String getAspectType() {
		return "RedisCacheableAspect";
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
		logResolvedCacheKey("@RedisCacheable", key);

		RegisterUtil.registerCachingInvocations(registry, method, targetBean, arguments, redisCacheable, key);
	}

}
