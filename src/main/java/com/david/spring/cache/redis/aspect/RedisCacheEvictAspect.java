package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.aspect.abstracts.AspectAbstract;
import com.david.spring.cache.redis.aspect.support.RegisterUtil;
import com.david.spring.cache.redis.registry.EvictInvocationRegistry;
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
public class RedisCacheEvictAspect extends AspectAbstract {

	private final EvictInvocationRegistry registry;

	public RedisCacheEvictAspect(EvictInvocationRegistry registry) {
		this.registry = registry;
	}

	@Around("@annotation(com.david.spring.cache.redis.annotation.RedisCacheEvict)")
	public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
		Method method = getSpecificMethod(joinPoint);
		log.debug("Processing @RedisCacheEvict annotation for method: {}.{}",
				method.getDeclaringClass().getSimpleName(), method.getName());
		try {
			return super.around(joinPoint);
		} catch (Exception e) {
			log.error("Failed to process cache evict for method: {}.{} - {}",
					method.getDeclaringClass().getSimpleName(), method.getName(), e.getMessage(), e);
			throw e;
		}
	}

	@Override
	public void registerInvocation(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
		Method method = getSpecificMethod(joinPoint);
		RedisCacheEvict redisCacheEvict = method.getAnnotation(RedisCacheEvict.class);
		if (redisCacheEvict == null) {
			log.warn("RedisCacheEvict annotation not found on method: {}.{}",
					method.getDeclaringClass().getSimpleName(), method.getName());
			return;
		}
		log.debug("Registering cache eviction for method: {}.{}",
				method.getDeclaringClass().getSimpleName(), method.getName());

		Object targetBean = joinPoint.getTarget();
		Object[] arguments = joinPoint.getArgs();

		boolean allEntries = redisCacheEvict.allEntries();
		Object key = null;
		if (!allEntries) {
			key = resolveCacheKey(targetBean, method, arguments, redisCacheEvict.keyGenerator());
		}
		RegisterUtil.registerEvictInvocation(registry, joinPoint, method, targetBean, arguments, redisCacheEvict, key);
	}

}
