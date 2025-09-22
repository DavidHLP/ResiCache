package com.david.spring.cache.redis.cache;

import com.david.spring.cache.redis.annotation.RedisCacheable;
import com.david.spring.cache.redis.core.RedisProCacheManager;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Component
@Slf4j
@Data
public class CacheExpireTime implements ApplicationListener<ContextRefreshedEvent> {
	private final RedisProCacheManager cacheManager;
	private final Map<String, Long> cacheTtlSeconds = new ConcurrentHashMap<>(32, 0.75f, 4);
	private final AtomicInteger processedBeans = new AtomicInteger(0);
	private final AtomicInteger processedMethods = new AtomicInteger(0);

	public CacheExpireTime(RedisProCacheManager cacheManager) {
		this.cacheManager = cacheManager;
	}

	@Override
	public void onApplicationEvent(@NonNull ContextRefreshedEvent event) {
		log.debug("Starting cache expiration time initialization");
		long startTime = System.currentTimeMillis();

		processedBeans.set(0);
		processedMethods.set(0);

		event.getApplicationContext()
				.getBeansWithAnnotation(Component.class)
				.values()
				.forEach(this::processBean);

		initializeCaches();

		long duration = System.currentTimeMillis() - startTime;
		log.info("Cache expiration time initialization completed in {}ms. Processed {} beans, {} methods",
				duration, processedBeans.get(), processedMethods.get());
	}

	private void processBean(Object bean) {
		try {
			Class<?> targetClass = AopUtils.getTargetClass(bean);
			processedBeans.incrementAndGet();

			for (Method method : targetClass.getMethods()) {
				processMethod(method, targetClass);
			}
		} catch (Exception e) {
			log.warn("Failed to process bean: {}, error: {}", bean.getClass().getSimpleName(), e.getMessage());
		}
	}

	private void processMethod(Method method, Class<?> targetClass) {
		try {
			Method specificMethod = ClassUtils.getMostSpecificMethod(method, targetClass);
			Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(specificMethod);
			RedisCacheable redisCacheable = AnnotatedElementUtils.findMergedAnnotation(bridgedMethod, RedisCacheable.class);

			if (redisCacheable != null) {
				initExpireTime(redisCacheable);
				processedMethods.incrementAndGet();
				log.trace("Processed cache annotation on method: {}.{}",
						targetClass.getSimpleName(), method.getName());
			}
		} catch (Exception e) {
			log.warn("Failed to process method {}.{}: {}",
					targetClass.getSimpleName(), method.getName(), e.getMessage());
		}
	}

	public void initExpireTime(RedisCacheable redisCacheable) {
		if (redisCacheable == null) return;
		String[] names = merge(redisCacheable.value(), redisCacheable.cacheNames());
		long ttl = Math.max(redisCacheable.ttl(), 0);
		for (String name : names) {
			if (name == null || name.isBlank()) continue;
			cacheTtlSeconds.merge(
					name.trim(),
					ttl,
					(oldVal, newVal) ->
							oldVal == 0 ? newVal : newVal == 0 ? oldVal : Math.min(oldVal, newVal));
		}
	}

	public void initializeCaches() {
		cacheTtlSeconds.forEach(
				(name, seconds) ->
						cacheManager
								.getRedisCacheConfigurationMap()
								.put(
										name,
										// 复用全局 RedisCacheConfiguration，确保序列化配置一致（JSON），仅覆盖 TTL
										cacheManager
												.getRedisCacheConfiguration()
												.entryTtl(Duration.ofSeconds(seconds))));
		cacheManager.initializeCaches();
	}

	private String[] merge(String[] a, String[] b) {
		return Stream.concat(
						Arrays.stream(Optional.ofNullable(a).orElse(new String[0])),
						Arrays.stream(Optional.ofNullable(b).orElse(new String[0])))
				.toArray(String[]::new);
	}
}
