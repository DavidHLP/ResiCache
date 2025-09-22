package com.david.spring.cache.redis.core;

import com.david.spring.cache.redis.core.holder.ApplicationContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.CacheResolver;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.expression.ExpressionParser;
import org.springframework.stereotype.Component;

/**
 * Bean解析工具类
 * 提供统一的KeyGenerator、CacheResolver和ExpressionParser解析逻辑
 */
@Slf4j
@Component
public class RedisProBeanResolver {

	/**
	 * 获取Bean实例的静态方法（用于非Spring管理的类调用）
	 */
	public static RedisProBeanResolver getInstance() {
		return ApplicationContextHolder.getBean(RedisProBeanResolver.class);
	}

	/**
	 * 解析KeyGenerator Bean
	 *
	 * @param keyGeneratorName Bean名称
	 * @param cached           已缓存的实例
	 * @return 解析的KeyGenerator实例
	 * @throws IllegalStateException 如果无法解析KeyGenerator Bean
	 */
	public KeyGenerator resolveKeyGenerator(String keyGeneratorName, KeyGenerator cached) {
		if (cached != null) {
			return cached;
		}

		if (keyGeneratorName == null || keyGeneratorName.isBlank()) {
			throw new IllegalArgumentException("KeyGenerator bean name cannot be null or blank");
		}

		// 只按名称解析，不提供任何降级策略
		KeyGenerator generator = ApplicationContextHolder.getBean(keyGeneratorName, KeyGenerator.class);
		if (generator != null) {
			log.debug("Successfully resolved KeyGenerator by name: {}", keyGeneratorName);
			return generator;
		}

		// 获取失败时直接抛出异常
		log.error("Critical error: KeyGenerator '{}' not found in Spring context", keyGeneratorName);
		throw new IllegalStateException("KeyGenerator '" + keyGeneratorName + "' not found in Spring context");
	}

	/**
	 * 解析CacheResolver Bean
	 *
	 * @param cacheResolverName Bean名称
	 * @param cached            已缓存的实例
	 * @return 解析的CacheResolver实例
	 */
	public CacheResolver resolveCacheResolver(String cacheResolverName, CacheResolver cached) {
		if (cached != null) {
			return cached;
		}

		if (cacheResolverName == null || cacheResolverName.isBlank()) {
			return null;
		}

		// 优先按名称解析
		CacheResolver resolver = ApplicationContextHolder.getBean(cacheResolverName, CacheResolver.class);
		if (resolver != null) {
			log.debug("Successfully resolved CacheResolver by name: {}", cacheResolverName);
			return resolver;
		}

		// 回退到按类型解析
		resolver = ApplicationContextHolder.getBean(CacheResolver.class);
		if (resolver != null) {
			log.debug("Successfully resolved CacheResolver by type");
		} else {
			log.warn("No CacheResolver found by name '{}' or type", cacheResolverName);
		}

		return resolver;
	}

	/**
	 * 解析ExpressionParser Bean
	 *
	 * @param expressionParserName Bean名称
	 * @param cached               已缓存的实例
	 * @return 解析的ExpressionParser实例
	 */
	public ExpressionParser resolveExpressionParser(String expressionParserName, ExpressionParser cached) {
		if (cached != null) {
			return cached;
		}

		if (expressionParserName == null || expressionParserName.isBlank()) {
			return null;
		}

		// 优先按名称解析
		ExpressionParser parser = ApplicationContextHolder.getBean(expressionParserName, ExpressionParser.class);
		if (parser != null) {
			log.debug("Successfully resolved ExpressionParser by name: {}", expressionParserName);
			return parser;
		}

		// 回退到按类型解析
		parser = ApplicationContextHolder.getBean(ExpressionParser.class);
		if (parser != null) {
			log.debug("Successfully resolved ExpressionParser by type");
		} else {
			log.warn("No ExpressionParser found by name '{}' or type", expressionParserName);
		}

		return parser;
	}
}