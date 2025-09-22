package com.david.spring.cache.redis.config;

import com.david.spring.cache.redis.core.RedisProCacheManager;
import com.david.spring.cache.redis.lock.DistributedLock;
import com.david.spring.cache.redis.protection.CacheBreakdown;
import com.david.spring.cache.redis.protection.CachePenetration;
import com.david.spring.cache.redis.registry.factory.RegistryFactory;
import com.david.spring.cache.redis.strategy.impl.CacheFetchStrategyManager;
import com.david.spring.cache.redis.strategy.support.CacheOperationService;
import com.david.spring.cache.redis.strategy.support.CacheContextValidator;
import com.david.spring.cache.redis.strategy.support.CacheStrategyExecutor;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

@Slf4j
@EnableCaching
@AutoConfiguration(after = RedisAutoConfiguration.class)
@ConditionalOnClass({RedisTemplate.class, RedisCacheWriter.class})
public class RedisCacheConfig {

	@Bean("redisProCacheManager")
	@ConditionalOnMissingBean(RedisProCacheManager.class)
	public RedisProCacheManager cacheManager(
			RedisConnectionFactory connectionFactory,
			RegistryFactory registryFactory,
			Executor cacheRefreshExecutor,
			DistributedLock distributedLock,
			CachePenetration cachePenetration,
			CacheBreakdown cacheBreakdown,
			CacheFetchStrategyManager strategyManager,
			CacheOperationService cacheOperationService,
			CacheContextValidator contextValidator,
			CacheStrategyExecutor strategyExecutor,
			CacheGuardProperties properties) {
		RedisCacheWriter cacheWriter =
				RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory);
		// Key 用 String，Value 用 JSON
		RedisSerializationContext.SerializationPair<String> keyPair =
				RedisSerializationContext.SerializationPair.fromSerializer(
						new StringRedisSerializer());
		// 配置支持 JavaTime 的 ObjectMapper
		ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.registerModule(new JavaTimeModule());
		objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		objectMapper.activateDefaultTyping(
				LaissezFaireSubTypeValidator.instance,
				ObjectMapper.DefaultTyping.NON_FINAL,
				JsonTypeInfo.As.PROPERTY);

		GenericJackson2JsonRedisSerializer jsonSerializer =
				new GenericJackson2JsonRedisSerializer(objectMapper);

		RedisSerializationContext.SerializationPair<Object> valuePair =
				RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer);

		RedisCacheConfiguration defaultCacheConfig =
				RedisCacheConfiguration.defaultCacheConfig()
						.entryTtl(Duration.ofSeconds(60))
						.serializeKeysWith(keyPair) // 覆盖 key 序列化
						.serializeValuesWith(valuePair); // 覆盖 value 序列化

		Map<String, RedisCacheConfiguration> initialCacheConfiguration = new HashMap<>();

		// 直接创建 RedisTemplate 实例
		RedisTemplate<String, Object> redisTemplate = createRedisTemplate(connectionFactory);

		return new RedisProCacheManager(
				cacheWriter,
				initialCacheConfiguration,
				redisTemplate,
				defaultCacheConfig,
				registryFactory,
				cacheRefreshExecutor,
				distributedLock,
				cachePenetration,
				cacheBreakdown,
				strategyManager,
				cacheOperationService,
				contextValidator,
				strategyExecutor,
				properties);
	}


	@Bean
	@ConditionalOnMissingBean(KeyGenerator.class)
	public KeyGenerator keyGenerator() {
		return new SimpleKeyGenerator();
	}

	@Bean(name = "cacheRefreshExecutor")
	@ConditionalOnMissingBean(name = "cacheRefreshExecutor")
	public Executor cacheRefreshExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(8);
		executor.setQueueCapacity(128);
		executor.setThreadNamePrefix("cache-refresh-");
		executor.setAwaitTerminationSeconds(5);
		executor.initialize();
		return executor;
	}

	/**
	 * 创建配置好的 RedisTemplate 实例
	 */
	private RedisTemplate<String, Object> createRedisTemplate(RedisConnectionFactory factory) {
		RedisTemplate<String, Object> template = new RedisTemplate<>();
		template.setConnectionFactory(factory);

		StringRedisSerializer stringSerializer = new StringRedisSerializer();
		// 为 RedisTemplate 创建一个支持 JavaTime 的 ObjectMapper
		ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.registerModule(new JavaTimeModule());
		objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		objectMapper.activateDefaultTyping(
				LaissezFaireSubTypeValidator.instance,
				ObjectMapper.DefaultTyping.NON_FINAL,
				JsonTypeInfo.As.PROPERTY);

		GenericJackson2JsonRedisSerializer jsonSerializer =
				new GenericJackson2JsonRedisSerializer(objectMapper);

		template.setKeySerializer(stringSerializer);
		template.setValueSerializer(jsonSerializer);
		template.setHashKeySerializer(stringSerializer);
		template.setHashValueSerializer(jsonSerializer);

		template.afterPropertiesSet();
		return template;
	}
}
