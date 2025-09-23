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

/**
 * 缓存过期时间配置管理器
 *
 * 负责在Spring容器启动时扫描所有带有@RedisCacheable注解的方法，
 * 收集缓存TTL配置信息并初始化缓存管理器中的缓存配置。
 *
 * 主要功能：
 * - 监听Spring容器刷新事件
 * - 扫描Bean中的缓存注解
 * - 收集和合并缓存TTL配置
 * - 初始化缓存配置
 */
@Component
@Slf4j
@Data
public class CacheExpireTime implements ApplicationListener<ContextRefreshedEvent> {
	private final RedisProCacheManager cacheManager;
	/** 缓存名称到TTL秒数的映射 */
	private final Map<String, Long> cacheTtlSeconds = new ConcurrentHashMap<>(32, 0.75f, 4);
	/** 已处理的Bean数量 */
	private final AtomicInteger processedBeans = new AtomicInteger(0);
	/** 已处理的方法数量 */
	private final AtomicInteger processedMethods = new AtomicInteger(0);

	/**
	 * 构造缓存过期时间管理器
	 *
	 * @param cacheManager Redis缓存管理器
	 */
	public CacheExpireTime(RedisProCacheManager cacheManager) {
		this.cacheManager = cacheManager;
	}

	/**
	 * 处理Spring容器刷新事件，扫描并初始化缓存配置
	 *
	 * @param event 容器刷新事件
	 */
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

	/**
	 * 处理单个Bean，扫描其中的缓存注解
	 *
	 * @param bean 待处理的Bean实例
	 */
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

	/**
	 * 处理单个方法，提取缓存注解信息
	 *
	 * @param method 待处理的方法
	 * @param targetClass 目标类
	 */
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

	/**
	 * 初始化缓存过期时间配置
	 *
	 * @param redisCacheable Redis缓存注解
	 */
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

	/**
	 * 初始化缓存配置，将收集到的TTL配置应用到缓存管理器
	 */
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

	/**
	 * 合并两个字符串数组
	 *
	 * @param a 数组A
	 * @param b 数组B
	 * @return 合并后的数组
	 */
	private String[] merge(String[] a, String[] b) {
		return Stream.concat(
						Arrays.stream(Optional.ofNullable(a).orElse(new String[0])),
						Arrays.stream(Optional.ofNullable(b).orElse(new String[0])))
				.toArray(String[]::new);
	}
}
