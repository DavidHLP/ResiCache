package com.david.spring.cache.redis.aspect;

import com.david.spring.cache.redis.annotation.RedisCacheEvict;
import com.david.spring.cache.redis.aspect.support.CacheAspectSupport;
import com.david.spring.cache.redis.reflect.EvictInvocation;
import com.david.spring.cache.redis.reflect.context.EvictInvocationContext;
import com.david.spring.cache.redis.registry.RegistryFactory;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Slf4j
@Aspect
@Component
public class RedisCacheEvictAspect extends AbstractCacheAspect {

	private final RegistryFactory registryFactory;
	private final CacheAspectSupport cacheSupport;

	public RedisCacheEvictAspect(RegistryFactory registryFactory, CacheAspectSupport cacheSupport) {
		this.registryFactory = registryFactory;
		this.cacheSupport = cacheSupport;
	}

	@Around("@annotation(redisCacheEvict)")
	public Object around(ProceedingJoinPoint joinPoint, RedisCacheEvict redisCacheEvict) throws Throwable {
		Method method = extractTargetMethod(joinPoint);
		Object targetBean = joinPoint.getTarget();
		Object[] arguments = joinPoint.getArgs();

		// 注册调用信息
		registerInvocation(redisCacheEvict, method, targetBean, arguments);

		// 执行缓存清除逻辑
		return cacheSupport.executeCacheEvict(joinPoint, redisCacheEvict, method, targetBean, arguments);
	}

	private void registerInvocation(RedisCacheEvict annotation, Method method, Object targetBean, Object[] arguments) {
		try {
			String[] cacheNames = cacheSupport.keyManager.getCacheNames(annotation.value(), annotation.cacheNames());

			final Object cacheKey;
			if (!annotation.allEntries()) {
				cacheKey = cacheSupport.keyManager.resolveKey(targetBean, method, arguments, annotation.keyGenerator());
			} else {
				cacheKey = null;
			}

			EvictInvocation evictInvocation = buildEvictInvocation(method, targetBean, arguments, annotation);
			final boolean allEntries = annotation.allEntries();
			final boolean beforeInvocation = annotation.beforeInvocation();

			cacheSupport.processValidCacheNames(cacheNames, method, cacheName -> {
				registryFactory.getEvictInvocationRegistry().register(cacheName, cacheKey, evictInvocation);
				log.debug("注册清除调用: cache={}, method={}, key={}, allEntries={}, beforeInvocation={}",
						cacheName, method.getName(), cacheKey, allEntries, beforeInvocation);
			});
		} catch (Exception e) {
			log.warn("注册清除调用失败: method={}, error={}", method.getName(), e.getMessage(), e);
		}
	}

	private EvictInvocation buildEvictInvocation(Method method, Object targetBean, Object[] arguments, RedisCacheEvict annotation) {
		EvictInvocationContext context = EvictInvocationContext.builder()
				.value(annotation.value())
				.cacheNames(annotation.cacheNames())
				.key(safeString(annotation.key()))
				.keyGenerator(annotation.keyGenerator())
				.cacheManager(annotation.cacheManager())
				.cacheResolver(annotation.cacheResolver())
				.condition(safeString(annotation.condition()))
				.allEntries(annotation.allEntries())
				.beforeInvocation(annotation.beforeInvocation())
				.sync(annotation.sync())
				.build();

		return EvictInvocation.builder()
				.arguments(arguments)
				.targetBean(targetBean)
				.targetMethod(method)
				.evictInvocationContext(context)
				.build();
	}
}