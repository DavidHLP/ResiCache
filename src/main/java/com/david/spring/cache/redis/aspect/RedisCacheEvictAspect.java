package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.aspect.abstracts.AspectAbstract;
import com.david.spring.cache.redis.aspect.constants.AspectConstants;
import com.david.spring.cache.redis.aspect.support.RegisterUtil;
import com.david.spring.cache.redis.registry.impl.EvictInvocationRegistry;
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
public class RedisCacheEvictAspect extends AspectAbstract {

	private final EvictInvocationRegistry registry;

	public RedisCacheEvictAspect(EvictInvocationRegistry registry) {
		this.registry = registry;
	}

	@Around("@annotation(com.david.spring.cache.redis.annotation.RedisCacheEvict)")
	public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
		Method method = getSpecificMethod(joinPoint);
		log.debug(AspectConstants.LogMessages.PROCESSING_METHOD,
				"@RedisCacheEvict", method.getDeclaringClass().getSimpleName(), method.getName());
		try {
			Object result = super.around(joinPoint);
			logProcessingSuccess(method);
			return result;
		} catch (Exception e) {
			log.error(AspectConstants.LogMessages.REGISTRATION_FAILED,
					getAspectType(), method.getDeclaringClass().getSimpleName(), method.getName(), e.getMessage(), e);
			throw e;
		}
	}

	@Override
	public void registerInvocation(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
		Method method = getSpecificMethod(joinPoint);
		RedisCacheEvict redisCacheEvict = method.getAnnotation(RedisCacheEvict.class);
		if (redisCacheEvict == null) {
			log.warn(AspectConstants.LogMessages.ANNOTATION_NOT_FOUND,
					"RedisCacheEvict", method.getDeclaringClass().getSimpleName(), method.getName());
			return;
		}

		Object targetBean = joinPoint.getTarget();
		Object[] arguments = joinPoint.getArgs();

		boolean allEntries = redisCacheEvict.allEntries();
		Object key = null;
		if (!allEntries) {
			key = resolveCacheKey(targetBean, method, arguments, redisCacheEvict.keyGenerator());
			logResolvedCacheKey("@RedisCacheEvict", key);
		} else {
			log.debug("Registering evict invocation for all entries");
		}
		RegisterUtil.registerEvictInvocation(registry, method, targetBean, arguments, redisCacheEvict, key);
	}

	@Override
	protected String getAspectType() {
		return "RedisCacheEvictAspect";
	}

}
